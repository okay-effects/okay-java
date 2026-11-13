package shop;

import payments.Pay;

/** a program that would pay around the audit, by the bank itself, and install the bank's handler by hand */
public final class Bypass {
  private Bypass() {}

  public static int checkout() throws Pay {
    payments.bank.Bank.transfer("thief", 900); // refused
    Object handler = payments.bank.Banking.class; // refused
    return Pay.balance();
  }
}
