package okay.rows.booking;

import static okay.rows.Effects.*;
import static org.junit.jupiter.api.Assertions.assertEquals;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import okay.effect.Eff;
import okay.effect.Pure;
import okay.rows.Comp;
import okay.rows.booking.Booking.*;
import org.junit.jupiter.api.Test;

/** The booking flow of okay-shop, ported ({@link Booking}), under handlers over a world in memory: the paths of the
 * original — taken, each refusal in its order, the area by its list and by its radius — and what each asked. */
class BookingTest {

  /** the world the handlers stand on: what a test sets, and what it reads after */
  static final class World {
    long now = 1_000_000L;
    boolean human = true;
    int ids;
    final Map<String, Integer> asked = new HashMap<>();
    final Set<Long> taken = new HashSet<>();
    final List<Booked> bookings = new ArrayList<>();
    final List<String> letters = new ArrayList<>();
    final List<String> notices = new ArrayList<>();
    final Map<String, Geo> places = new HashMap<>();
    final List<String> log = new ArrayList<>();
  }

  // ---- the handlers: one function each

  static <A, R> Comp<A, R> stored(World w, Comp<A, ? super Eff<Store<?>, R>> body) {
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

  static <A, R> Comp<A, R> mailed(World w, Comp<A, ? super Eff<Mail<?>, R>> body) {
    return answering(op -> switch (op) {
      case Mail.ToClient m -> { w.letters.add(m.to() + ": " + m.text()); yield m.answer(true); }
      case Mail.ToOwner m -> { w.letters.add("owner: " + m.text()); yield m.answer(true); }
    }, body);
  }

  static <A, R> Comp<A, R> verified(World w, Comp<A, ? super Eff<Captcha<?>, R>> body) {
    return answering(op -> switch (op) {
      case Captcha.Verify v -> { w.log.add("captcha"); yield v.answer(w.human); }
    }, body);
  }

  static <A, R> Comp<A, R> identified(World w, Comp<A, ? super Eff<Ids<?>, R>> body) {
    return answering(op -> switch (op) {
      case Ids.Ref r -> r.answer(r.prefix() + (++w.ids));
      case Ids.Secret s -> s.answer("key" + (++w.ids));
    }, body);
  }

  static <A, R> Comp<A, R> clocked(World w, Comp<A, ? super Eff<Clock<?>, R>> body) {
    return answering(op -> switch (op) {
      case Clock.Now n -> n.answer(w.now);
    }, body);
  }

  static <A, R> Comp<A, R> notified(World w, Comp<A, ? super Eff<Notify<?>, R>> body) {
    return answering(op -> switch (op) {
      case Notify.Tell t -> { w.notices.add(t.notice()); yield t.answer(null); }
    }, body);
  }

  static <A, R> Comp<A, R> geocoded(World w, Comp<A, ? super Eff<Geocoder<?>, R>> body) {
    return answering(op -> switch (op) {
      case Geocoder.Find f -> {
        w.log.add("geocoder");
        yield f.answer(Optional.ofNullable(w.places.get(f.address().city())));
      }
    }, body);
  }

  /** the entry point: the handlers in the order of the row, the program run once */
  Response book(World w, Conf c, Map<String, String> form) {
    Comp<Response, Pure> p = geocoded(w, notified(w, clocked(w, identified(w, verified(w, mailed(w, stored(w,
        Booking.book(c, form, "10.0.0.1"))))))));
    return run(p);
  }

  static final Conf SHOP = new Conf(
      Map.of("cut", new Offer("cut", 30, 80, false), "old", new Offer("old", 30, 50, true)),
      Optional.of(new Area(List.of("Gdynia"), 10, new Geo(54.5, 18.5), 20)), "S", "policy-1", "owner@shop");

  static Map<String, String> form(String... more) {
    Map<String, String> f = new HashMap<>(Map.of("name", "Ann", "contact", "ann@mail", "rodo_consent", "true",
        "service", "cut", "start", "5000"));
    for (int i = 0; i < more.length; i += 2) f.put(more[i], more[i + 1]);
    return f;
  }

  @Test
  void aBookingTaken() {
    World w = new World();
    assertEquals(new Response(200, "booked SB1"), book(w, SHOP, form()));
    assertEquals(List.of(new Booked("SB1", "key2", "cut", 5000, "Ann", "ann@mail", w.now, Optional.empty(), 80)),
        w.bookings);
    assertEquals(List.of("ann@mail: booked SB1", "owner: booked SB1 by Ann"), w.letters);
    assertEquals(List.of("booked SB1"), w.notices);
    // the limit, the captcha, then the store's step: no geocoder for a booking at the shop
    assertEquals(List.of("store", "captcha", "store"), w.log);
  }

  /** each refusal in the original's order, and nothing asked after it */
  @Test
  void theRefusalsInTheirOrder() {
    World w = new World();
    assertEquals(new Response(400, "refused: name"), book(w, SHOP, form("name", " ")));
    assertEquals(new Response(400, "refused: contact"), book(w, SHOP, form("contact", "nobody")));
    assertEquals(new Response(400, "refused: consent"), book(w, SHOP, form("rodo_consent", "false")));
    assertEquals(new Response(400, "refused: service"), book(w, SHOP, form("service", "old")));
    assertEquals(List.of(), w.log);
    w.human = false;
    assertEquals(new Response(403, "refused: captcha"), book(w, SHOP, form()));
    assertEquals(List.of("store", "captcha"), w.log);
    assertEquals(List.of(), w.letters);
  }

  @Test
  void aSlotTakenAndTheLimit() {
    World w = new World();
    assertEquals(200, book(w, SHOP, form()).status());
    assertEquals(new Response(409, "refused: taken"), book(w, SHOP, form()));
    assertEquals(1, w.bookings.size());
    assertEquals(2, w.letters.size());
    for (int i = 0; i < 3; i++) book(w, SHOP, form("start", "" + (6000 + i)));
    assertEquals(new Response(429, "refused: limit"), book(w, SHOP, form("start", "9000")));
  }

  /** at the client's: inside by the list asks no geocoder; by the radius it does; an address not found is not a no */
  @Test
  void theAreaByItsListAndByItsRadius() {
    World w = new World();
    w.places.put("Sopot", new Geo(54.45, 18.56));
    w.places.put("Warszawa", new Geo(52.2, 21.0));
    Map<String, String> home = form("mode", "client", "street", "Morska 1", "postcode", "81-001", "city", "Gdynia");
    assertEquals(new Response(200, "booked SB1"), book(w, SHOP, home));
    assertEquals(100, w.bookings.get(0).price());
    assertEquals(List.of("store", "captcha", "store"), w.log);
    w.log.clear();
    home.put("city", "Sopot");
    home.put("start", "7000");
    assertEquals(200, book(w, SHOP, home).status());
    assertEquals(List.of("geocoder", "store", "captcha", "store"), w.log);
    home.put("city", "Warszawa");
    assertEquals(new Response(409, "refused: area"), book(w, SHOP, home));
    home.put("city", "Nowhere");
    assertEquals(new Response(409, "refused: area-unchecked"), book(w, SHOP, home));
    home.put("postcode", "81001");
    assertEquals(new Response(400, "refused: address"), book(w, SHOP, home));
  }
}
