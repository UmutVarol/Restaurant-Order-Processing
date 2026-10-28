package com.umutvarol.restaurant.fiscal;

/** The two legal documents a restaurant in Türkiye issues for a sale. */
public enum FiscalKind {
    /** Printed by the yeni nesil ÖKC (cash register) at the till: cash and card sales. */
    RECEIPT,
    /** Issued through a GİB-authorised integrator: online payments, and guests who ask for an invoice. */
    E_ARSIV
}
