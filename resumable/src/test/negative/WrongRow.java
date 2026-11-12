import okay.resumable.Counter;
import okay.resumable.Direct;
import okay.resumable.Pick;
import okay.rows.Comp;
import okay.rows.Eff;
import okay.rows.Pure;

/** a row at the boundary that leaves out an effect the body throws, or is not the row the program is given */
class WrongRow {
  static int both() throws Counter, Pick {
    return Counter.get() + Pick.among(1, 2);
  }

  static Comp<Integer, Eff<Counter, Pure>> missing() {
    return Direct.row(Counter.class).of(WrongRow::both); // refused
  }

  static Comp<Integer, Eff<Pick, Eff<Counter, Pure>>> reordered() {
    return Direct.row(Counter.class, Pick.class).of(WrongRow::both); // refused
  }

  static Comp<Integer, Eff<Counter, Eff<Pick, Pure>>> right() {
    return Direct.row(Counter.class, Pick.class).of(WrongRow::both);
  }
}
