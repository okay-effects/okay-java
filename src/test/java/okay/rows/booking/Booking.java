package okay.rows.booking;

import static okay.rows.Effects.*;

import java.util.List;
import java.util.Map;
import java.util.Optional;
import okay.effect.Eff;
import okay.effect.Operation;
import okay.rows.Comp;
import okay.rows.Var;

/**
 * A REAL PROGRAM, ported: the taking of a booking of okay-shop ({@code okay.shop.app.Bookings.book}, Scala on
 * okay-scala) — its checks in their order, its seven effects, its sub-programs of fewer effects — on the light
 * form.
 * The model is cut down to what the flow reads; the flow is the original's.
 */
final class Booking {
  private Booking() {}

  // ---- the model, cut down

  record Offer(String key, int minutes, long price, boolean hidden) {}
  record Address(String street, String postcode, String city) {}
  record Geo(double lat, double lon) {}
  record Area(List<String> cities, int radiusKm, Geo centre, long fee) {}
  record Conf(Map<String, Offer> offers, Optional<Area> area, String refPrefix, String policy, String owner) {}
  record Plan(Offer offer, boolean atClient, Optional<Address> address, long price) {}
  record Booked(String ref, String key, String service, long start, String name, String contact, long consentAt,
      Optional<Address> address, long price) {}
  record Response(int status, String body) {}

  /** a thing done or refused with a reason: the original's Either[String, A] */
  record Or<A>(String refused, A value) {
    static <A> Or<A> of(A value) { return new Or<>(null, value); }
    static <A> Or<A> refused(String why) { return new Or<>(why, null); }
    boolean ok() { return refused == null; }
  }

  // ---- the effects: the original's seven

  sealed interface Store<T> extends Operation<T, Store<?>> {
    record Allow(String key, int max, long window, long now) implements Store<Boolean> {}
    record Book(Booked booking, long now) implements Store<Or<Booked>> {}
  }
  sealed interface Mail<T> extends Operation<T, Mail<?>> {
    record ToClient(String to, String text) implements Mail<Boolean> {}
    record ToOwner(String text) implements Mail<Boolean> {}
  }
  sealed interface Captcha<T> extends Operation<T, Captcha<?>> {
    record Verify(String token, String ip) implements Captcha<Boolean> {}
  }
  sealed interface Ids<T> extends Operation<T, Ids<?>> {
    record Ref(String prefix) implements Ids<String> {}
    record Secret() implements Ids<String> {}
  }
  sealed interface Clock<T> extends Operation<T, Clock<?>> {
    record Now() implements Clock<Long> {}
  }
  sealed interface Notify<T> extends Operation<T, Notify<?>> {
    record Tell(String notice) implements Notify<Void> {}
  }
  sealed interface Geocoder<T> extends Operation<T, Geocoder<?>> {
    record Find(Address address) implements Geocoder<Optional<Geo>> {}
  }

  static final long TEN_MINUTES = 600_000L;

  // ---- the checks that ask nothing

  static Response refused(String why, int status) { return new Response(status, "refused: " + why); }
  static Response refused(String why) { return refused(why, 400); }

  static Or<Plan> plan(Conf c, String service, String mode) {
    Offer o = c.offers().get(service);
    if (o == null || o.hidden()) return Or.refused("service");
    boolean atClient = mode.equals("client");
    if (atClient && c.area().isEmpty()) return Or.refused("mode");
    return Or.of(new Plan(o, atClient, Optional.empty(), o.price() + (atClient ? c.area().get().fee() : 0)));
  }

  static double km(Geo a, Geo b) {
    return Math.hypot(a.lat() - b.lat(), a.lon() - b.lon()) * 111;
  }

  // ---- the sub-programs, each of the effects it needs

  /** INSIDE THE AREA: by its list; else, with a radius, the address found and measured from the centre — empty when
   * that could not be checked */
  static Comp<Optional<Boolean>, Eff<Geocoder<?>, ?>> inside(Conf c, Address a) {
    if (c.area().isEmpty()) return pure(Optional.of(false));
    Area area = c.area().get();
    if (area.cities().contains(a.city())) return pure(Optional.of(true));
    if (area.radiusKm() <= 0) return pure(Optional.of(false));
    return perform(new Geocoder.Find(a)).map(found -> found.map(g -> km(area.centre(), g) <= area.radiusKm()));
  }

  /** the plan with the client's address when it is at theirs: given whole and inside the area, or why not */
  static Comp<Or<Plan>, Eff<Geocoder<?>, ?>> addressed(Conf c, Or<Plan> planned, Map<String, String> f) {
    if (!planned.ok() || !planned.value().atClient()) return pure(planned);
    Plan p = planned.value();
    Address a = new Address(f.getOrDefault("street", "").trim(), f.getOrDefault("postcode", "").trim(),
        f.getOrDefault("city", "").trim());
    if (a.street().isEmpty() || a.city().isEmpty() || !a.postcode().matches("[0-9]{2}-[0-9]{3}"))
      return pure(Or.refused("address"));
    return inside(c, a).map(in -> in.isEmpty() ? Or.<Plan>refused("area-unchecked")
        : in.get() ? Or.of(new Plan(p.offer(), true, Optional.of(a), p.price())) : Or.<Plan>refused("area"));
  }

  /** A BOOKING TAKEN, no letter yet: its reference and its key, then the store decides in its step */
  static Comp<Or<Booked>, Eff<Store<?>, ? extends Eff<Ids<?>, ?>>> reserve(
      Conf c, Plan p, long at, String name, String contact, long now) {
    return together(t -> {
      Var<String> ref = t.ask(up(perform(new Ids.Ref(c.refPrefix() + "B"))));
      Var<String> key = t.ask(up(perform(new Ids.Secret())));
      Var<Or<Booked>> made = t.ask(ref, key, (r, k) -> perform(new Store.Book(
          new Booked(r, k, p.offer().key(), at, name, contact, now, p.address(), p.price()), now)));
      return t.yield(made);
    });
  }

  /** the client and the owner told of a booking, and the owner's notice: a letter is never the booking */
  static Comp<Void, Eff<Mail<?>, ? extends Eff<Notify<?>, ?>>> told(Conf c, Booked b) {
    return together(t -> {
      t.perform(new Mail.ToClient(b.contact(), "booked " + b.ref()));
      t.perform(new Mail.ToOwner("booked " + b.ref() + " by " + b.name()));
      t.ask(up(perform(new Notify.Tell("booked " + b.ref()))));
      return t.done();
    });
  }

  // ---- the flow itself: seven effects, in the order of their handlers

  /** the booking made if its start is still free — the store decides —, then the letters and the notice */
  static Comp<Response, Eff<Store<?>, ? extends Eff<Mail<?>, ? extends Eff<Captcha<?>, ? extends Eff<Ids<?>,
      ? extends Eff<Clock<?>, ? extends Eff<Notify<?>, ?>>>>>>>
      taken(Conf c, Plan p, long at, String name, String contact, long now) {
    return under(under(reserve(c, p, at, name, contact, now))).flatMap(made ->
        !made.ok() ? pure(refused(made.refused(), 409))
            : up(under(under(under(told(c, made.value()))))).map(done ->
                new Response(200, "booked " + made.value().ref())));
  }

  static Comp<Response, Eff<Store<?>, Eff<Mail<?>, Eff<Captcha<?>, Eff<Ids<?>, Eff<Clock<?>, Eff<Notify<?>,
      Eff<Geocoder<?>, ?>>>>>>>> book(Conf c, Map<String, String> f, String ip) {
    String name = f.getOrDefault("name", "").trim();
    String contact = f.getOrDefault("contact", "").trim();
    Long start = f.containsKey("start") ? Long.valueOf(f.get("start")) : null;
    if (name.isEmpty()) return pure(refused("name"));
    if (!contact.contains("@")) return pure(refused("contact"));
    if (!"true".equals(f.get("rodo_consent"))) return pure(refused("consent"));
    Or<Plan> planned = plan(c, f.getOrDefault("service", ""), f.getOrDefault("mode", ""));
    return up(up(up(up(up(up(addressed(c, planned, f))))))).flatMap(checked -> {
      if (!checked.ok()) return pure(refused(checked.refused(), checked.refused().startsWith("area") ? 409 : 400));
      if (start == null) return pure(refused("start"));
      Plan p = checked.value();
      return up(up(up(up(perform(new Clock.Now()))))).flatMap(now ->
          perform(new Store.Allow("book:" + ip, 5, TEN_MINUTES, now)).flatMap(allowed ->
              !allowed ? pure(refused("limit", 429))
                  : up(up(perform(new Captcha.Verify(f.getOrDefault("token", ""), ip)))).flatMap(human ->
                      !human ? pure(refused("captcha", 403)) : taken(c, p, start, name, contact, now))));
    });
  }
}
