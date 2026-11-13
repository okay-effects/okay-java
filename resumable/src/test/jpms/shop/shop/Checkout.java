package shop;

import payments.Pay;

/** a program: it pays by performing the effect, so the audit sees every payment */
public final class Checkout {
  private Checkout() {}

  public static int checkout() throws Pay {
    Pay.transfer("shop", 100);
    return Pay.balance();
  }
}
