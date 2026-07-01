package com.logicvoid0x.billing.catalog.store;

/** A catalog read or write failed. */
public class CatalogException extends RuntimeException {
  private static final long serialVersionUID = 1L;

  /** Creates the exception. */
  public CatalogException(String message, Throwable cause) {
    super(message, cause);
  }

  /** Creates the exception. */
  public CatalogException(String message) {
    super(message);
  }
}
