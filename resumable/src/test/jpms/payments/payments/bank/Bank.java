package payments.bank;

import okay.resumable.Implementation;

/** the real client: not exported, so no program can call it */
@Implementation
public final class Bank {
  private Bank() {}

  static int balance = 1000;

  public static String transfer(String to, int amount) {
    balance -= amount;
    return "ok:" + to + ":" + amount;
  }

  public static int balance() {
    return balance;
  }
}
