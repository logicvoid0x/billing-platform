package com.logicvoid0x.billing.contracts.catalog;

/** The catalog service could not be reached or did not return a usable snapshot. */
public final class CatalogUnavailableException extends RuntimeException {
  private static final long serialVersionUID = 1L;

  /** Creates the exception. */
  public CatalogUnavailableException(String message) {
    super(message);
  }

  /** Creates the exception with its cause. */
  public CatalogUnavailableException(String message, Throwable cause) {
    super(message, cause);
  }
}
