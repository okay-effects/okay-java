package okay.rows.booking;

import static okay.rows.Effects.run;

import java.util.Map;
import okay.effect.Pure;
import okay.rows.Comp;
import okay.rows.booking.Booking.Conf;
import okay.rows.booking.Booking.Response;

/** The same scenarios ({@link BookingTest}) and the same seven handlers on the flow whose row is named
 * ({@link BookingAlias}). */
class BookingAliasTest extends BookingTest {
  @Override
  Response book(World w, Conf c, Map<String, String> form) {
    Comp<Response, Pure> p = geocoded(w, notified(w, clocked(w, identified(w, verified(w, mailed(w, stored(w,
        BookingAlias.open(BookingAlias.book(c, form, "10.0.0.1")))))))));
    return run(p);
  }
}
