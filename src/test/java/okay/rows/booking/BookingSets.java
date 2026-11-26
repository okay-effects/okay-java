package okay.rows.booking;

import static okay.rows.Sets.*;

import java.util.Map;
import java.util.Optional;
import okay.effect.Operation;
import okay.rows.Comp;
import okay.rows.booking.Booking.*;

/**
 * THE SAME FLOW ({@link Booking}) ON ROWS AS SETS ({@link okay.rows.Sets}): each program says the effects it asks,
 * in any order, and is used inside a program of more as it is — no place of an effect, no {@code up}, no name for
 * the row. The model and the checks that ask nothing are {@link Booking}'s; the effects are declared here, each
 * operation naming its answer and its effect.
 */
final class BookingSets {
  private BookingSets() {}

  // ---- the effects: the original's seven

  sealed interface Store {
    record Allow(String key, int max, long window, long now) implements Store, Operation<Boolean, Store> {}
    record Book(Booked booking, long now) implements Store, Operation<Or<Booked>, Store> {}
  }
  sealed interface Mail {
    record ToClient(String to, String text) implements Mail, Operation<Boolean, Mail> {}
    record ToOwner(String text) implements Mail, Operation<Boolean, Mail> {}
  }
  sealed interface Captcha {
    record Verify(String token, String ip) implements Captcha, Operation<Boolean, Captcha> {}
  }
  sealed interface Ids {
    record Ref(String prefix) implements Ids, Operation<String, Ids> {}
    record Secret() implements Ids, Operation<String, Ids> {}
  }
  sealed interface Clock {
    record Now() implements Clock, Operation<Long, Clock> {}
  }
  sealed interface Notify {
    record Tell(String notice) implements Notify, Operation<Void, Notify> {}
  }
  sealed interface Geocoder {
    record Find(Address address) implements Geocoder, Operation<Optional<Geo>, Geocoder> {}
  }

  // ---- the sub-programs, each of the effects it needs

  static <R extends Geocoder> Comp<Optional<Boolean>, R> inside(Conf c, Address a) {
    if (c.area().isEmpty()) return pure(Optional.of(false));
    Area area = c.area().get();
    if (area.cities().contains(a.city())) return pure(Optional.of(true));
    if (area.radiusKm() <= 0) return pure(Optional.of(false));
    return perform(new Geocoder.Find(a)).map(found ->
        found.map(g -> Booking.km(area.centre(), g) <= area.radiusKm()));
  }

  static <R extends Geocoder> Comp<Or<Plan>, R> addressed(Conf c, Or<Plan> planned, Map<String, String> f) {
    if (!planned.ok() || !planned.value().atClient()) return pure(planned);
    Plan p = planned.value();
    Address a = new Address(f.getOrDefault("street", "").trim(), f.getOrDefault("postcode", "").trim(),
        f.getOrDefault("city", "").trim());
    if (a.street().isEmpty() || a.city().isEmpty() || !a.postcode().matches("[0-9]{2}-[0-9]{3}"))
      return pure(Or.refused("address"));
    return inside(c, a).map(in -> in.isEmpty() ? Or.<Plan>refused("area-unchecked")
        : in.get() ? Or.of(new Plan(p.offer(), true, Optional.of(a), p.price())) : Or.<Plan>refused("area"));
  }

  static <R extends Ids & Store> Comp<Or<Booked>, R> reserve(
      Conf c, Plan p, long at, String name, String contact, long now) {
    return perform(new Ids.Ref(c.refPrefix() + "B")).flatMap(ref ->
        perform(new Ids.Secret()).flatMap(key ->
            perform(new Store.Book(new Booked(ref, key, p.offer().key(), at, name, contact, now, p.address(),
                p.price()), now))));
  }

  static <R extends Mail & Notify> Comp<Void, R> told(Conf c, Booked b) {
    return perform(new Mail.ToClient(b.contact(), "booked " + b.ref())).flatMap(sent ->
        perform(new Mail.ToOwner("booked " + b.ref() + " by " + b.name())).flatMap(told ->
            perform(new Notify.Tell("booked " + b.ref()))));
  }

  // ---- the flow itself

  static <R extends Ids & Store & Mail & Notify> Comp<Response, R> taken(
      Conf c, Plan p, long at, String name, String contact, long now) {
    return reserve(c, p, at, name, contact, now).flatMap(made ->
        !made.ok() ? pure(Booking.refused(made.refused(), 409))
            : told(c, made.value()).map(done -> new Response(200, "booked " + made.value().ref())));
  }

  static <R extends Store & Mail & Captcha & Ids & Clock & Notify & Geocoder> Comp<Response, R> book(
      Conf c, Map<String, String> f, String ip) {
    String name = f.getOrDefault("name", "").trim();
    String contact = f.getOrDefault("contact", "").trim();
    Long start = f.containsKey("start") ? Long.valueOf(f.get("start")) : null;
    if (name.isEmpty()) return pure(Booking.refused("name"));
    if (!contact.contains("@")) return pure(Booking.refused("contact"));
    if (!"true".equals(f.get("rodo_consent"))) return pure(Booking.refused("consent"));
    Or<Plan> planned = Booking.plan(c, f.getOrDefault("service", ""), f.getOrDefault("mode", ""));
    return addressed(c, planned, f).flatMap(checked -> {
      if (!checked.ok())
        return pure(Booking.refused(checked.refused(), checked.refused().startsWith("area") ? 409 : 400));
      if (start == null) return pure(Booking.refused("start"));
      Plan p = checked.value();
      return perform(new Clock.Now()).flatMap(now ->
          perform(new Store.Allow("book:" + ip, 5, Booking.TEN_MINUTES, now)).flatMap(allowed ->
              !allowed ? pure(Booking.refused("limit", 429))
                  : perform(new Captcha.Verify(f.getOrDefault("token", ""), ip)).flatMap(human ->
                      !human ? pure(Booking.refused("captcha", 403)) : taken(c, p, start, name, contact, now))));
    });
  }
}
