package okay.resumable;

import static okay.rows.Sets.*;
import static org.junit.jupiter.api.Assertions.assertEquals;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import okay.effect.Eff;
import okay.effect.Operation;
import okay.resumable.Direct.Asks;
import okay.rows.Comp;
import org.junit.jupiter.api.Test;

/**
 * A REAL PROGRAM IN PLAIN JAVA: the taking of a booking of okay-shop ({@code okay.shop.app.Bookings.book}; the
 * root module's {@code okay.rows.booking} has it as chains and blocks) — its checks in their order with early
 * returns, its seven effects, its helper methods of fewer effects — as a body of a row that is a set
 * ({@code Direct.body}). The model is cut down to what the flow reads.
 */
class BookingBodyTest {

  // ---- the model, cut down

  record Offer(String key, long price, boolean hidden) {}
  record Address(String street, String postcode, String city) {}
  record Geo(double lat, double lon) {}
  record Area(List<String> cities, int radiusKm, Geo centre, long fee) {}
  record Conf(Map<String, Offer> offers, Optional<Area> area, String refPrefix) {}
  record Booked(String ref, String key, String service, long start, String name, String contact,
      Optional<Address> address, long price) {}
  record Response(int status, String body) {}
  record Or<A>(String refused, A value) {
    static <A> Or<A> of(A value) { return new Or<>(null, value); }
    static <A> Or<A> refused(String why) { return new Or<>(why, null); }
    boolean ok() { return refused == null; }
  }

  // ---- the effects

  sealed interface Store {
    record Allow(String key, int max, long now) implements Store, Operation<Boolean, Store> {}
    record Book(Booked booking, long now) implements Store, Operation<Or<Booked>, Store> {}
  }
  sealed interface Mail {
    record ToClient(String to, String text) implements Mail, Operation<Boolean, Mail> {}
    record ToOwner(String text) implements Mail, Operation<Boolean, Mail> {}
  }
  sealed interface Captcha { record Verify(String token, String ip) implements Captcha, Operation<Boolean, Captcha> {} }
  sealed interface Ids {
    record Ref(String prefix) implements Ids, Operation<String, Ids> {}
    record Secret() implements Ids, Operation<String, Ids> {}
  }
  sealed interface Clock { record Now() implements Clock, Operation<Long, Clock> {} }
  sealed interface Notify { record Tell(String notice) implements Notify, Operation<Void, Notify> {} }
  sealed interface Geocoder { record Find(Address address) implements Geocoder, Operation<Optional<Geo>, Geocoder> {} }

  static Response refused(String why, int status) { return new Response(status, "refused: " + why); }

  // ---- the flow: plain methods, each of the effects it asks

  /** inside the area: by its list; else by its radius, the address found — empty when that could not be checked */
  static <R extends Geocoder> Optional<Boolean> inside(Asks<R> d, Area area, Address a) {
    if (area.cities().contains(a.city())) return Optional.of(true);
    if (area.radiusKm() <= 0) return Optional.of(false);
    Optional<Geo> found = d.perform(new Geocoder.Find(a));
    return found.map(g -> Math.hypot(area.centre().lat() - g.lat(), area.centre().lon() - g.lon()) * 111
        <= area.radiusKm());
  }

  /** the client and the owner told, and the owner's notice */
  static <R extends Mail & Notify> void told(Asks<R> d, Booked b) {
    d.perform(new Mail.ToClient(b.contact(), "booked " + b.ref()));
    d.perform(new Mail.ToOwner("booked " + b.ref() + " by " + b.name()));
    d.perform(new Notify.Tell("booked " + b.ref()));
  }

  static <R extends Store & Mail & Captcha & Ids & Clock & Notify & Geocoder> Comp<Response, R> book(
      Conf c, Map<String, String> f, String ip) {
    return Direct.body(d -> {
      String name = f.getOrDefault("name", "").trim();
      String contact = f.getOrDefault("contact", "").trim();
      if (name.isEmpty()) return refused("name", 400);
      if (!contact.contains("@")) return refused("contact", 400);
      if (!"true".equals(f.get("rodo_consent"))) return refused("consent", 400);
      Offer offer = c.offers().get(f.getOrDefault("service", ""));
      if (offer == null || offer.hidden()) return refused("service", 400);
      boolean atClient = "client".equals(f.get("mode"));
      if (atClient && c.area().isEmpty()) return refused("mode", 400);

      Optional<Address> address = Optional.empty();
      long price = offer.price();
      if (atClient) {
        Area area = c.area().get();
        Address a = new Address(f.getOrDefault("street", "").trim(), f.getOrDefault("postcode", "").trim(),
            f.getOrDefault("city", "").trim());
        if (a.street().isEmpty() || a.city().isEmpty() || !a.postcode().matches("[0-9]{2}-[0-9]{3}"))
          return refused("address", 400);
        Optional<Boolean> in = inside(d, area, a);
        if (in.isEmpty()) return refused("area-unchecked", 409);
        if (!in.get()) return refused("area", 409);
        address = Optional.of(a);
        price += area.fee();
      }
      if (!f.containsKey("start")) return refused("start", 400);
      long start = Long.parseLong(f.get("start"));

      long now = d.perform(new Clock.Now());
      if (!d.perform(new Store.Allow("book:" + ip, 5, now))) return refused("limit", 429);
      if (!d.perform(new Captcha.Verify(f.getOrDefault("token", ""), ip))) return refused("captcha", 403);

      String ref = d.perform(new Ids.Ref(c.refPrefix() + "B"));
      String key = d.perform(new Ids.Secret());
      Or<Booked> made = d.perform(new Store.Book(
          new Booked(ref, key, offer.key(), start, name, contact, address, price), now));
      if (!made.ok()) return refused(made.refused(), 409);
      told(d, made.value());
      return new Response(200, "booked " + made.value().ref());
    });
  }

  // ---- the world the handlers stand on, and the handlers: one function each

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

  static <A, R> Comp<A, R> stored(World w, Comp<A, ? super Eff<Store, R>> body) {
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
  static <A, R> Comp<A, R> mailed(World w, Comp<A, ? super Eff<Mail, R>> body) {
    return answering(op -> switch (op) {
      case Mail.ToClient m -> { w.letters.add(m.to() + ": " + m.text()); yield m.answer(true); }
      case Mail.ToOwner m -> { w.letters.add("owner: " + m.text()); yield m.answer(true); }
    }, body);
  }
  static <A, R> Comp<A, R> verified(World w, Comp<A, ? super Eff<Captcha, R>> body) {
    return answering(op -> switch (op) {
      case Captcha.Verify v -> { w.log.add("captcha"); yield v.answer(w.human); }
    }, body);
  }
  static <A, R> Comp<A, R> identified(World w, Comp<A, ? super Eff<Ids, R>> body) {
    return answering(op -> switch (op) {
      case Ids.Ref r -> r.answer(r.prefix() + (++w.ids));
      case Ids.Secret s -> s.answer("key" + (++w.ids));
    }, body);
  }
  static <A, R> Comp<A, R> clocked(World w, Comp<A, ? super Eff<Clock, R>> body) {
    return answering(op -> switch (op) { case Clock.Now n -> n.answer(w.now); }, body);
  }
  static <A, R> Comp<A, R> notified(World w, Comp<A, ? super Eff<Notify, R>> body) {
    return answering(op -> switch (op) {
      case Notify.Tell t -> { w.notices.add(t.notice()); yield t.answer(null); }
    }, body);
  }
  static <A, R> Comp<A, R> geocoded(World w, Comp<A, ? super Eff<Geocoder, R>> body) {
    return answering(op -> switch (op) {
      case Geocoder.Find f -> {
        w.log.add("geocoder");
        yield f.answer(Optional.ofNullable(w.places.get(f.address().city())));
      }
    }, body);
  }

  static Response book(World w, Conf c, Map<String, String> form) {
    var shop = row(of(Store.class, Mail.class, Captcha.class, Ids.class, Clock.class, Notify.class, Geocoder.class));
    return run(geocoded(w, notified(w, clocked(w, identified(w, verified(w, mailed(w, stored(w,
        shop.of(book(c, form, "10.0.0.1"))))))))));
  }

  static final Conf SHOP = new Conf(
      Map.of("cut", new Offer("cut", 80, false), "old", new Offer("old", 50, true)),
      Optional.of(new Area(List.of("Gdynia"), 10, new Geo(54.5, 18.5), 20)), "S");

  static Map<String, String> form(String... more) {
    Map<String, String> f = new HashMap<>(Map.of("name", "Ann", "contact", "ann@mail", "rodo_consent", "true",
        "service", "cut", "start", "5000"));
    for (int i = 0; i < more.length; i += 2) f.put(more[i], more[i + 1]);
    return f;
  }

  // ---- the scenarios of the original

  @Test
  void aBookingTaken() {
    World w = new World();
    assertEquals(new Response(200, "booked SB1"), book(w, SHOP, form()));
    assertEquals(List.of(new Booked("SB1", "key2", "cut", 5000, "Ann", "ann@mail", Optional.empty(), 80)),
        w.bookings);
    assertEquals(List.of("ann@mail: booked SB1", "owner: booked SB1 by Ann"), w.letters);
    assertEquals(List.of("booked SB1"), w.notices);
    assertEquals(List.of("store", "captcha", "store"), w.log);
  }

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
    for (int i = 0; i < 3; i++) book(w, SHOP, form("start", "" + (6000 + i)));
    assertEquals(new Response(429, "refused: limit"), book(w, SHOP, form("start", "9000")));
  }

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
