package com.logicvoid0x.billing.catalog.store;

/**
 * A write would edit an existing catalog row or overlap its period. Catalog rows are never edited
 * (ADR 0004): a change is a new effective-dated row.
 */
public final class CatalogEditRejectedException extends CatalogException {
  private static final long serialVersionUID = 1L;

  /** Creates the exception. */
  public CatalogEditRejectedException(String message) {
    super(message);
  }

  /** Creates the exception. */
  public CatalogEditRejectedException(String message, Throwable cause) {
    super(message, cause);
  }
}
