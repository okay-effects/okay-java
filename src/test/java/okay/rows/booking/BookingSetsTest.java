package okay.rows.booking;

import static okay.rows.Sets.*;

import java.util.Map;
import java.util.Optional;
import okay.rows.Comp;
import okay.rows.Sets.And;
import okay.rows.booking.Booking.Conf;
import okay.rows.booking.Booking.Or;
import okay.rows.booking.Booking.Response;
import okay.rows.booking.BookingSets.*;

/** The same scenarios ({@link BookingTest}) over the same world, on the flow whose rows are sets
 * ({@link BookingSets}): its handlers functions as before, the set written as a list where they are put. */
class BookingSetsTest extends BookingTest {

  static final class On {
    static <A, R> Comp<A, R> stored(World w, Comp<A, ? super And<Store, R>> body) {
      return answering(op -> {
        w.log.add("store");
        return switch (op) {
          case Store.Allow a -> a.answer(w.asked.merge(a.key(), 1, Integer::sum) <= a.max());
          case Store.Book b -> {
            if (!w.taken.add(b.booking().start())) yield b.answer(Or.refused("taken"));
            w.bookings.add(b.booking());
            yield b.answer(Or.of(b.booking()));
          }
        };
      }, body);
    }

    static <A, R> Comp<A, R> mailed(World w, Comp<A, ? super And<Mail, R>> body) {
      return answering(op -> switch (op) {
        case Mail.ToClient m -> { w.letters.add(m.to() + ": " + m.text()); yield m.answer(true); }
        case Mail.ToOwner m -> { w.letters.add("owner: " + m.text()); yield m.answer(true); }
      }, body);
    }

    static <A, R> Comp<A, R> verified(World w, Comp<A, ? super And<Captcha, R>> body) {
      return answering(op -> switch (op) {
        case Captcha.Verify v -> { w.log.add("captcha"); yield v.answer(w.human); }
      }, body);
    }

    static <A, R> Comp<A, R> identified(World w, Comp<A, ? super And<Ids, R>> body) {
      return answering(op -> switch (op) {
        case Ids.Ref r -> r.answer(r.prefix() + (++w.ids));
        case Ids.Secret s -> s.answer("key" + (++w.ids));
      }, body);
    }

    static <A, R> Comp<A, R> clocked(World w, Comp<A, ? super And<Clock, R>> body) {
      return answering(op -> switch (op) { case Clock.Now n -> n.answer(w.now); }, body);
    }

    static <A, R> Comp<A, R> notified(World w, Comp<A, ? super And<Notify, R>> body) {
      return answering(op -> switch (op) {
        case Notify.Tell t -> { w.notices.add(t.notice()); yield t.answer(null); }
      }, body);
    }

    static <A, R> Comp<A, R> geocoded(World w, Comp<A, ? super And<Geocoder, R>> body) {
      return answering(op -> switch (op) {
        case Geocoder.Find f -> {
          w.log.add("geocoder");
          yield f.answer(Optional.ofNullable(w.places.get(f.address().city())));
        }
      }, body);
    }
  }

  @Override
  Response book(World w, Conf c, Map<String, String> form) {
    var shop = row(of(Store.class, Mail.class, Captcha.class, Ids.class, Clock.class, Notify.class, Geocoder.class));
    return run(On.geocoded(w, On.notified(w, On.clocked(w, On.identified(w, On.verified(w, On.mailed(w,
        On.stored(w, shop.of(BookingSets.book(c, form, "10.0.0.1"))))))))));
  }
}
