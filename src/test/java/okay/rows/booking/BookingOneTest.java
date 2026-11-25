package okay.rows.booking;

import static okay.rows.Effects.*;

import java.util.Map;
import java.util.Optional;
import okay.effect.Answer;
import okay.effect.Pure;
import okay.rows.Comp;
import okay.rows.booking.Booking.Conf;
import okay.rows.booking.Booking.Or;
import okay.rows.booking.Booking.Response;
import okay.rows.booking.BookingOne.*;

/** The same scenarios ({@link BookingTest}) on the flow whose effects are one place of the row ({@link BookingOne}):
 * one handler, a switch over the application's effects, each answered by its own function. */
class BookingOneTest extends BookingTest {

  static Answer<?> stored(World w, Store<?> op) {
    w.log.add("store");
    return switch (op) {
      case Store.Allow a -> a.answer(w.asked.merge(a.key(), 1, Integer::sum) <= a.max());
      case Store.Book b -> {
        if (!w.taken.add(b.booking().start())) yield b.answer(Or.refused("taken"));
        w.bookings.add(b.booking());
        yield b.answer(Or.of(b.booking()));
      }
    };
  }

  static Answer<?> mailed(World w, Mail<?> op) {
    return switch (op) {
      case Mail.ToClient m -> { w.letters.add(m.to() + ": " + m.text()); yield m.answer(true); }
      case Mail.ToOwner m -> { w.letters.add("owner: " + m.text()); yield m.answer(true); }
    };
  }

  static Answer<?> identified(World w, Ids<?> op) {
    return switch (op) {
      case Ids.Ref r -> r.answer(r.prefix() + (++w.ids));
      case Ids.Secret s -> s.answer("key" + (++w.ids));
    };
  }

  @Override
  Response book(World w, Conf c, Map<String, String> form) {
    Comp<Response, Pure> p = answering(op -> switch (op) {
      case Store<?> s -> stored(w, s);
      case Mail<?> m -> mailed(w, m);
      case Ids<?> i -> identified(w, i);
      case Captcha.Verify v -> { w.log.add("captcha"); yield v.answer(w.human); }
      case Clock.Now n -> n.answer(w.now);
      case Notify.Tell t -> { w.notices.add(t.notice()); yield t.answer(null); }
      case Geocoder.Find f -> {
        w.log.add("geocoder");
        yield f.answer(Optional.ofNullable(w.places.get(f.address().city())));
      }
    }, BookingOne.book(c, form, "10.0.0.1"));
    return run(p);
  }
}
