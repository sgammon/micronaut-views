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


import com.google.common.collect.ImmutableMap;
import com.google.template.soy.msgs.SoyMsgBundle;

import javax.annotation.Nonnull;
import javax.annotation.concurrent.Immutable;
import java.io.File;
import java.io.IOException;
import java.net.URL;
import java.util.Collections;
import java.util.Map;
import java.util.Optional;


/**
 * Data class representing render flow context for a single Soy template render execution. Holds regular render values
 * and injected render values.
 *
 * @author Sam Gammon (sam@momentum.io)
 * @since 1.3.4
 */
@Immutable
@SuppressWarnings("WeakerAccess")
public final class SoyContext implements SoyContextMediator {
  /** Properties for template render. */
  private final @Nonnull Map<String, Object> props;

  /** Injected values for template render. */
  private final @Nonnull Map<String, Object> injected;

  /** Naming map provider. Overrides globally-installed provider if set. */
  private final @Nonnull Optional<SoyNamingMapProvider> overrideNamingMap;

  /** Messages bundle to apply (for internationalization). */
  private final @Nonnull Optional<SoyI18NContext> i18n;

  /**
   * Private constructor. See static factory methods to create a new `SoyContext`.
   *
   * @param props Properties/values to make available via `@param` declarations.
   * @param injected Properties/values to make available via `@inject` declarations.
   * @param overrideNamingMap Naming map to apply, overrides any global rewrite map.
   * @param i18n Translation data or pre-constructed configuration.
   */
  private SoyContext(@Nonnull Map<String, Object> props,
                     @Nonnull Map<String, Object> injected,
                     @Nonnull Optional<SoyNamingMapProvider> overrideNamingMap,
                     @Nonnull Optional<SoyI18NContext> i18n) {
    this.props = ImmutableMap.copyOf(props);
    this.injected = ImmutableMap.copyOf(injected);
    this.overrideNamingMap = overrideNamingMap;
    this.i18n = i18n;
  }

  /**
   * Create a new `SoyContext` object from a map of properties, additionally specifying any properties made available
   * via `@inject` declarations in the template to be rendered.
   *
   * @param props Properties to attach to this Soy render context.
   * @param injected Injected properties and values to attach to this context.
   * @param overrideNamingMap Naming map to use for this execution, if renaming is enabled.
   * @param i18n Translation data or pre-constructed configuration.
   * @return Instance of `SoyContext` that holds the properties specified.
   * @throws IllegalArgumentException If any provided argument is `null`. Pass an empty map or an empty `Optional`.
   */
  public static SoyContext fromMap(@Nonnull Map<String, Object> props,
                                   @Nonnull Optional<Map<String, Object>> injected,
                                   @Nonnull Optional<SoyNamingMapProvider> overrideNamingMap,
                                   @Nonnull Optional<SoyI18NContext> i18n) {
    //noinspection ConstantConditions,OptionalAssignedToNull
    if (props == null || injected == null || overrideNamingMap == null || i18n == null) {
      throw new IllegalArgumentException(
        "Must provide empty maps, or `Optional.empty()` where applicable, instead of `null` to `SoyContext`.");
    }
    return new SoyContext(
      props,
      injected.orElse(Collections.emptyMap()),
      overrideNamingMap,
      i18n);
  }

  // -- Public API -- //

  /**
   * Retrieve properties which should be made available via regular, declared `@param` statements.
   *
   * @return Map of regular template properties.
   */
  @Override @Nonnull
  public Map<String, Object> getProperties() {
    return props;
  }

  /**
   * Retrieve properties and values that should be made available via `@inject`, additionally specifying an optional
   * overlay of properties to apply before returning.
   *
   * @param framework Properties injected by the framework.
   * @return Map of injected properties and their values.
   */
  @Override
  @SuppressWarnings("UnstableApiUsage")
  public @Nonnull Map<String, Object> getInjectedProperties(@Nonnull Map<String, Object> framework) {
    ImmutableMap.Builder<String, Object> merged = ImmutableMap.builderWithExpectedSize(
      injected.size() + framework.size());
    merged.putAll(injected);
    merged.putAll(framework);
    return merged.build();
  }

  /**
   * Specify a Soy renaming map which overrides the globally-installed map, if any. Renaming must still be activated via
   * config, or manually, for the return value of this method to have any effect.
   *
   * @return {@link SoyNamingMapProvider} that should be used for this render routine.
   */
  @Override @Nonnull
  public Optional<SoyNamingMapProvider> overrideNamingMap() {
    return overrideNamingMap;
  }

  /**
   * If a messages file was specified, return it here.
   *
   * @return Messages file, if specified.
   */
  @Override @Nonnull
  public Optional<File> messagesFile() {
    if (i18n.isPresent() && i18n.get().getMessageFile().isPresent()) {
      return i18n.get().getMessageFile();
    }
    return Optional.empty();
  }

  /**
   * If a messages resource (usually on the classpath) was specified, return it here.
   *
   * @return Messages resource, if specified.
   */
  @Override @Nonnull
  public Optional<URL> messagesResource() {
    if (i18n.isPresent() && i18n.get().getMessageResource().isPresent()) {
      return i18n.get().getMessageResource();
    }
    return Optional.empty();
  }

  /**
   * If a pre-constructed messages bundle was specified, return it here. Otherwise, fall back to default behavior, which
   * involves spawning a messages bundle from either a messages file, or a messages resource, if either are specified.
   * If neither are specified, {@link Optional#empty()} is returned.
   *
   * @return Pre-constructed message bundle, or resolved message bundle, or {@link Optional#empty()}.
   * @throws IOException If the message bundle cannot be loaded and the routine encounters an error.
   */
  @Override @Nonnull
  public Optional<SoyMsgBundle> messageBundle() throws IOException {
    if (i18n.isPresent() && i18n.get().getMessageBundle().isPresent()) {
      return i18n.get().getMessageBundle();
    }
    return SoyContextMediator.super.messageBundle();
  }

  /**
   * Holds onto context related to internationalization via XLIFF message files.
   */
  public static final class SoyI18NContext {
    /** Pre-constructed message bundle. */
    private final @Nonnull Optional<SoyMsgBundle> messageBundle;

    /** Messages file to load. */
    private final @Nonnull Optional<File> messageFile;

    /** Messages resource to load. */
    private final @Nonnull Optional<URL> messageResource;

    /**
     * Construct a Soy Internationalization Context from scratch.
     *
     * @param messageBundle Pre-constructed messages bundle, if available.
     * @param messageFile Messages data file, if one should be loaded.
     * @param messageResource Messages resource URL, if one should be loaded.
     */
    public SoyI18NContext(@Nonnull Optional<SoyMsgBundle> messageBundle,
                          @Nonnull Optional<File> messageFile,
                          @Nonnull Optional<URL> messageResource) {
      this.messageBundle = messageBundle;
      this.messageFile = messageFile;
      this.messageResource = messageResource;
    }

    /** @return Pre-constructed message bundle, if available. */
    @Nonnull public Optional<SoyMsgBundle> getMessageBundle() {
      return messageBundle;
    }

    /** @return Messages data file, if one should be loaded. */
    @Nonnull public Optional<File> getMessageFile() {
      return messageFile;
    }

    /** @return Messages data URL, if data should be loaded. Usually a classpath resource. */
    @Nonnull public Optional<URL> getMessageResource() {
      return messageResource;
    }
  }
}
