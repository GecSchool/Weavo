package com.weavo.siteaccess;

import java.net.URI;

/** Coordinates one outbound request, including requests for robots rules and redirect targets. */
@FunctionalInterface
public interface RequestCoordinator {
  Permit acquire(URI target, WorkBudget budget);

  @FunctionalInterface
  interface Permit extends AutoCloseable {
    /** Implementations may fail when a permit has expired or its ownership is lost. */
    default void check() {}

    @Override
    void close();
  }
}
