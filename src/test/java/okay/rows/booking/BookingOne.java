package okay.rows.booking;

import static okay.rows.Effects.*;

import java.util.Map;
import java.util.Optional;
import okay.effect.Eff;
import okay.effect.Operation;
import okay.rows.Comp;
import okay.rows.Var;
import okay.rows.booking.Booking.*;

/**
 * THE SAME FLOW ({@link Booking}), ITS EFFECTS ONE PLACE OF THE ROW: the application's effects are one sealed family,
 * {@code Shop}, and each of the seven a sealed part of it. A program's row is {@code Eff<Shop<?>, ?>} whatever it
 * asks: no {@code up}, no place to count, a sub-program used as it is. What is given up: the type no longer says
 * which of the seven a function asks, and they are handled together.
 */
final class BookingOne {
  private BookingOne() {}

  sealed interface Shop<T> extends Operation<T, Shop<?>> permits Store, Mail, Captcha, Ids, Clock, Notify, Geocoder {}

  sealed interface Store<T> extends Shop<T> {
    record Allow(String key, int max, long window, long now) implements Store<Boolean> {}
    record Book(Booked booking, long now) implements Store<Or<Booked>> {}
  }
  sealed interface Mail<T> extends Shop<T> {
    record ToClient(String to, String text) implements Mail<Boolean> {}
    record ToOwner(String text) implements Mail<Boolean> {}
  }
  sealed interface Captcha<T> extends Shop<T> {
    record Verify(String token, String ip) implements Captcha<Boolean> {}
  }
  sealed interface Ids<T> extends Shop<T> {
    record Ref(String prefix) implements Ids<String> {}
    record Secret() implements Ids<String> {}
  }
  sealed interface Clock<T> extends Shop<T> {
    record Now() implements Clock<Long> {}
  }
  sealed interface Notify<T> extends Shop<T> {
    record Tell(String notice) implements Notify<Void> {}
  }
  sealed interface Geocoder<T> extends Shop<T> {
    record Find(Address address) implements Geocoder<Optional<Geo>> {}
  }

  static Comp<Optional<Boolean>, Eff<Shop<?>, ?>> inside(Conf c, Address a) {
    if (c.area().isEmpty()) return pure(Optional.of(false));
    Area area = c.area().get();
    if (area.cities().contains(a.city())) return pure(Optional.of(true));
    if (area.radiusKm() <= 0) return pure(Optional.of(false));
    return perform(new Geocoder.Find(a)).map(found ->
        found.map(g -> Booking.km(area.centre(), g) <= area.radiusKm()));
  }

  static Comp<Or<Plan>, Eff<Shop<?>, ?>> addressed(Conf c, Or<Plan> planned, Map<String, String> f) {
    if (!planned.ok() || !planned.value().atClient()) return pure(planned);
    Plan p = planned.value();
    Address a = new Address(f.getOrDefault("street", "").trim(), f.getOrDefault("postcode", "").trim(),
        f.getOrDefault("city", "").trim());
    if (a.street().isEmpty() || a.city().isEmpty() || !a.postcode().matches("[0-9]{2}-[0-9]{3}"))
      return pure(Or.refused("address"));
    return inside(c, a).map(in -> in.isEmpty() ? Or.<Plan>refused("area-unchecked")
        : in.get() ? Or.of(new Plan(p.offer(), true, Optional.of(a), p.price())) : Or.<Plan>refused("area"));
  }

  static Comp<Or<Booked>, Eff<Shop<?>, ?>> reserve(Conf c, Plan p, long at, String name, String contact, long now) {
    return together(t -> {
      Var<String> ref = t.perform(new Ids.Ref(c.refPrefix() + "B"));
      Var<String> key = t.perform(new Ids.Secret());
      Var<Or<Booked>> made = ref.with(key, (r, k) -> new Booked(r, k, p.offer().key(), at, name, contact, now,
          p.address(), p.price())).then(b -> t.perform(new Store.Book(b, now)));
      return t.yield(made);
    });
  }

  static Comp<Void, Eff<Shop<?>, ?>> told(Conf c, Booked b) {
    return together(t -> {
      t.perform(new Mail.ToClient(b.contact(), "booked " + b.ref()));
      t.perform(new Mail.ToOwner("booked " + b.ref() + " by " + b.name()));
      t.perform(new Notify.Tell("booked " + b.ref()));
      return t.done();
    });
  }

  static Comp<Response, Eff<Shop<?>, ?>> taken(Conf c, Plan p, long at, String name, String contact, long now) {
    return reserve(c, p, at, name, contact, now).flatMap(made ->
        !made.ok() ? pure(Booking.refused(made.refused(), 409))
            : told(c, made.value()).map(done -> new Response(200, "booked " + made.value().ref())));
  }

  static Comp<Response, Eff<Shop<?>, ?>> book(Conf c, Map<String, String> f, String ip) {
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
