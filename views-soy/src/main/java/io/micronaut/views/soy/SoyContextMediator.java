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


import com.google.template.soy.msgs.SoyMsgBundle;
import com.google.template.soy.msgs.SoyMsgBundleHandler;
import com.google.template.soy.xliffmsgplugin.XliffMsgPlugin;
import io.micronaut.http.HttpRequest;
import io.micronaut.http.MutableHttpResponse;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;
import java.io.File;
import java.io.IOException;
import java.net.URL;
import java.security.MessageDigest;
import java.util.Map;
import java.util.Optional;
import java.util.function.Predicate;


/**
 * Interface by which Soy render context can be managed and orchestrated by a custom {@link SoyContext} object. Provides
 * the ability to specify variables for <pre>@param</pre> and <pre>@inject</pre> Soy declarations, and the ability to
 * override objects like the {@link SoyNamingMapProvider}.
 *
 * @author Sam Gammon (sam@momentum.io)
 * @see SoyContext Default implementation of this interface
 * @since 1.3.2
 */
public interface SoyContextMediator {
  /** @return Whether to enable {@code ETag} support within the Soy rendering layer. */
  default boolean enableETags() {
    return false;
  }

  /** @return Whether to calculate strong {@code ETag} values while rendering. */
  default boolean strongETags() {
    return false;
  }

  /**
   * Retrieve properties which should be made available via regular, declared `@param` statements.
   *
   * @return Map of regular template properties.
   */
  @Nonnull Map<String, Object> getProperties();

  /**
   * Retrieve properties and values that should be made available via `@inject`.
   *
   * @param framework Properties auto-injected by the framework.
   * @return Map of injected properties and their values.
   */
  @Nonnull Map<String, Object> getInjectedProperties(Map<String, Object> framework);

  /**
   * Specify a Soy renaming map which overrides the globally-installed map, if any. Renaming must still be activated via
   * config, or manually, for the return value of this method to have any effect.
   *
   * @return {@link SoyNamingMapProvider} that should be used for this render routine.
   */
  default @Nonnull Optional<SoyNamingMapProvider> overrideNamingMap() {
    return Optional.empty();
  }

  /**
   * Whether to translate content with message bundles. Should return as `true` when a message bundle should be applied
   * via {@link #messagesFile()}, {@link #messagesResource()} or {@link #messageBundle()}. This method must return
   * `true` along with configuration being set to `true` in relevant ways (i.e. the `i18n` setting, via
   * {@link SoyViewsRendererConfiguration}).
   *
   * @return Whether to attempt translation via mediated message files (specified via {@link #messagesFile()} and
   *         friends). Defaults to `false`.
   */
  default boolean translate() {
    return false;
  }

  /**
   * Return the messages file (XLFF) which should be applied to the template set before rendering. If provided, the XLFF
   * plugin is injected when the template bundle is resolved.
   *
   * @return Selected message file for the request.
   */
  default @Nonnull Optional<File> messagesFile() {
    return Optional.empty();
  }

  /**
   * Return the messages resource (XLFF) which should be applied to the template set before rendering. If provided, the
   * XLFF plugin is injected when the template bundle is resolved.
   *
   * @return Selected message resource URL for the request.
   */
  default @Nonnull Optional<URL> messagesResource() {
    return Optional.empty();
  }

  /**
   * Return the delegate package that should be set as active for a given Soy render run.
   *
   * @return Delegate package string.
   */
  default @Nonnull Optional<Predicate<String>> delegatePackage() {
    return Optional.empty();
  }

  /**
   * Return the resolved message bundle for a given template run. How the message bundle is created is abstracted away
   * from this method, but the default implementation calls {@link #messagesFile()} to produce a file, and falls back to
   * {@link #messagesResource()} from there. If no file or resource are available, {@link Optional#empty()} is
   * propagated as the return value.
   *
   * @return Soy message bundle to use for a template render call.
   * @throws IOException If an error is encountered loading the messages file.
   */
  default @Nonnull Optional<SoyMsgBundle> messageBundle() throws IOException {
    Optional<File> messagesFile = messagesFile();
    Optional<URL> messagesResource = messagesResource();
    if (messagesFile.isPresent() || messagesResource.isPresent()) {
      SoyMsgBundleHandler msgBundleHandler = new SoyMsgBundleHandler(new XliffMsgPlugin());
      if (messagesFile.isPresent()) {
        return Optional.of(msgBundleHandler.createFromFile(messagesFile.get()));
      } else {
        return Optional.of(msgBundleHandler.createFromResource(messagesResource.get()));
      }
    }
    return Optional.empty();
  }

  /**
   * Finalize an HTTP response rendered by the Micronaut Soy layer. This may include adding any final headers, or
   * adjusting headers, before the response is sent.
   *
   * @param request Request that produced this response.
   * @param response HTTP response to finalize.
   * @param body Rendered HTTP response body.
   * @param digester Pre-filled message digest for the first chunk. Only provided if enabled by {@link #enableETags()}.
   * @param <T> Body object type.
   * @return Response, but finalized.
   */
  default @Nonnull <T> MutableHttpResponse<T> finalizeResponse(@Nonnull HttpRequest<?> request,
                                                               @Nonnull MutableHttpResponse<T> response,
                                                               @Nonnull T body,
                                                               @Nullable MessageDigest digester) {
    return response.body(body);
  }
}
