/*
 * Copyright 2017-2019 original authors
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package io.micronaut.views.soy;

import com.google.template.soy.SoyFileSet;
import com.google.template.soy.data.SoyValueProvider;
import com.google.template.soy.data.TemplateParameters;
import com.google.template.soy.jbcsrc.api.RenderResult;
import com.google.template.soy.jbcsrc.api.SoySauce;
import com.google.template.soy.shared.SoyCssRenamingMap;
import com.google.template.soy.shared.SoyIdRenamingMap;
import io.micronaut.context.annotation.Requires;
import io.micronaut.core.io.Writable;
import io.micronaut.core.util.ArgumentUtils;
import io.micronaut.http.HttpRequest;
import io.micronaut.http.MediaType;
import io.micronaut.http.MutableHttpResponse;
import io.micronaut.http.annotation.Produces;
import io.micronaut.views.AsyncViewsRenderer;
import io.micronaut.views.ViewsConfiguration;
import io.micronaut.views.csp.CspConfiguration;
import io.micronaut.views.csp.CspFilter;
import io.micronaut.views.exceptions.ViewRenderingException;
import io.reactivex.Flowable;
import org.reactivestreams.Publisher;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;
import javax.inject.Inject;
import javax.inject.Singleton;
import java.io.IOException;
import java.util.HashMap;
import java.util.Map;
import java.util.Optional;


/**
 * Renders views with a Soy Tofu-based engine.
 *
 * @author Sam Gammon (sam@bloombox.io)
 * @since 1.3.0
 */
@Produces(MediaType.TEXT_HTML)
@Requires(property = SoyViewsRendererConfigurationProperties.PREFIX + ".engine", notEquals = "tofu")
@Requires(classes = SoySauce.class)
@Singleton
@SuppressWarnings({"WeakerAccess", "UnstableApiUsage"})
public class SoySauceViewsRenderer implements AsyncViewsRenderer {

  private static final Logger LOG = LoggerFactory.getLogger(SoySauceViewsRenderer.class);
  private static final String INJECTED_NONCE_PROPERTY = "csp_nonce";

  protected final ViewsConfiguration viewsConfiguration;
  protected final SoyViewsRendererConfigurationProperties soyMicronautConfiguration;
  protected final SoyNamingMapProvider namingMapProvider;
  protected final SoySauce soySauce;
  private final boolean injectNonce;

  /**
   * @param viewsConfiguration Views configuration properties.
   * @param namingMapProvider Provider for renaming maps in Soy.
   * @param cspConfiguration Content-Security-Policy configuration.
   * @param namingMapProvider Soy naming map provider
   * @param soyConfiguration   Soy configuration properties.
   */
  @Inject
  SoySauceViewsRenderer(ViewsConfiguration viewsConfiguration,
                        @Nullable CspConfiguration cspConfiguration,
                        @Nullable SoyNamingMapProvider namingMapProvider,
                        CspConfiguration cspConfiguration,
                        SoyViewsRendererConfigurationProperties soyConfiguration) {
    this.viewsConfiguration = viewsConfiguration;
    this.soyMicronautConfiguration = soyConfiguration;
    this.injectNonce = cspConfiguration.isNonceEnabled();
    this.namingMapProvider = namingMapProvider;
    this.injectNonce = cspConfiguration != null && cspConfiguration.isNonceEnabled();
    final SoySauce precompiled = soyConfiguration.getCompiledTemplates();
    if (precompiled != null) {
      this.soySauce = precompiled;
    } else {
      LOG.warn("Compiling Soy templates (this may take a moment)...");
      SoyFileSet fileSet = soyConfiguration.getFileSet();
      if (fileSet == null) {
        throw new IllegalStateException(
          "Unable to load Soy templates: no file set, no compiled templates provided.");
      }
      this.soySauce = soyConfiguration.getFileSet().compileTemplates();
    }
  }

  private Publisher<MutableHttpResponse<Writable>> continueRender(@Nonnull SoySauce.WriteContinuation continuation,
                                                                  @Nonnull MutableHttpResponse<Writable> response,
                                                                  @Nonnull AppendableToWritable target) {
    try {
      @SuppressWarnings("BlockingMethodInNonBlockingContext")
      SoySauce.WriteContinuation next = continuation.continueRender();
      return handleRender(next, response, target);

    } catch (IOException ioe) {
      LOG.warn("Soy encountered IOException while rendering: '" + ioe.getMessage() + "'.");
      return Flowable.error(ioe);

    }
  }

  @Nonnull
  private Publisher<MutableHttpResponse<Writable>> handleRender(@Nonnull SoySauce.WriteContinuation continuation,
                                                                @Nonnull MutableHttpResponse<Writable> response,
                                                                @Nonnull AppendableToWritable target) {
    RenderResult.Type resultType = continuation.result().type();
    switch (resultType) {
      // If it's done, break and provide it to Micronaut.
      case DONE: break;

      // Render engine is signalling that we are waiting on an async task.
      case DETACH:
        return Flowable.fromFuture(continuation.result().future())
          .switchMap((i) -> this.continueRender(continuation, response, target));

      // Output buffer is full: indicate backpressure.
      // @TODO(sgammon): this will never happen because AppendableToWritable doesn't yet support the interface.
      // once it's clear how to indicate backpressure, this should be hooked up.
      case LIMITED: break;

      default: throw new IllegalArgumentException(
        "Unrecognized continuation result: '" + resultType.name() + "'.");
    }
    return Flowable.just(response.body(target));
  }

  /**
   * @param viewName view name to be render
   * @param data     response body to render it with a view
   * @param request  HTTP request
   * @return Publisher that emits the HTTP response when ready
   */
  @Nonnull
  @Override
  public Publisher<MutableHttpResponse<Writable>> render(@Nonnull String viewName,
                                                         @Nullable Object data,
                                                         @Nonnull HttpRequest<?> request,
                                                         @Nonnull MutableHttpResponse<Writable> response) {
    ArgumentUtils.requireNonNull("viewName", viewName);

    Map<String, Object> ijOverlay = new HashMap<>(1);
    Map<String, Object> context = modelOf(data);
    final SoySauce.Renderer renderer = soySauce.newRenderer(new TemplateParameters() {
      @Override
      public String getTemplateName() {
        return viewName;
      }

      @Override
      public Map<String, SoyValueProvider> getParamsAsMap() {
        return null;
      }
    });
    renderer.setData(context);
    if (injectNonce) {
      Optional<Object> nonceObj = request.getAttribute(CspFilter.NONCE_PROPERTY);
      if (nonceObj.isPresent()) {
        String nonceValue = ((String) nonceObj.get());
        ijOverlay.put(INJECTED_NONCE_PROPERTY, nonceValue);
      }
    }
    renderer.setIj(ijOverlay);

    if (this.soyMicronautConfiguration.isRenamingEnabled() && this.namingMapProvider != null) {
      SoyCssRenamingMap cssMap = this.namingMapProvider.cssRenamingMap();
      SoyIdRenamingMap idMap = this.namingMapProvider.idRenamingMap();
      if (cssMap != null) {
        renderer.setCssRenamingMap(cssMap);
      }
      if (idMap != null) {
        renderer.setXidRenamingMap(idMap);
      }
    }

    try {
      final AppendableToWritable target = new AppendableToWritable();
      SoySauce.WriteContinuation state;

      //noinspection BlockingMethodInNonBlockingContext
      state = renderer.renderHtml(target);
      return handleRender(state, response, target);

    } catch (IOException e) {
      return Flowable.error(new ViewRenderingException(
        "Error rendering Soy Sauce view [" + viewName + "]: " + e.getMessage(), e));

    }
  }

  /**
   * @param view view name to be rendered
   * @return true if a template can be found for the supplied view name.
   */
  @Override
  public boolean exists(@Nonnull String view) {
    return soySauce.hasTemplate(view);
  }

}
