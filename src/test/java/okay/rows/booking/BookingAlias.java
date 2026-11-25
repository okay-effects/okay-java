package okay.rows.booking;

import static okay.rows.Effects.*;

import java.util.Map;
import java.util.Optional;
import okay.effect.Eff;
import okay.rows.Comp;
import okay.rows.Var;
import okay.rows.booking.Booking.*;

/**
 * THE SAME FLOW ({@link Booking}), ITS ROW NAMED ONCE: an interface that extends the row, its tail a parameter —
 * {@code interface App<R> extends Eff<Store<?>, Eff<Mail<?>, .. R>>>}. Every program of the application is a
 * {@code Comp<X, App<?>>}; each effect is asked by one word that knows its place, written once and checked by javac.
 * The effects are {@link Booking}'s own, each its own place, each its own handler, their order in the type.
 */
final class BookingAlias {
  private BookingAlias() {}

  /** the application's row, named: the seven effects in the order of their handlers, whatever comes after */
  interface App<R> extends Eff<Store<?>, Eff<Mail<?>, Eff<Captcha<?>, Eff<Ids<?>, Eff<Clock<?>, Eff<Notify<?>,
      Eff<Geocoder<?>, R>>>>>>> {}

  /** the row itself, for the handlers: what {@code App<R>} stands for */
  @SuppressWarnings("unchecked") // a row is only a type, and App<R> is this row by its declaration
  static <A, R> Comp<A, Eff<Store<?>, Eff<Mail<?>, Eff<Captcha<?>, Eff<Ids<?>, Eff<Clock<?>, Eff<Notify<?>,
      Eff<Geocoder<?>, R>>>>>>>> open(Comp<A, ? super App<R>> c) {
    return (Comp<A, Eff<Store<?>, Eff<Mail<?>, Eff<Captcha<?>, Eff<Ids<?>, Eff<Clock<?>, Eff<Notify<?>,
        Eff<Geocoder<?>, R>>>>>>>>) (Comp<A, ?>) c;
  }

  // ---- each effect's word: its place in the row, said once
  static <T> Comp<T, App<?>> store(Store<T> op) { return fit(perform(op)); }
  static <T> Comp<T, App<?>> mail(Mail<T> op) { return fit(up(perform(op))); }
  static <T> Comp<T, App<?>> captcha(Captcha<T> op) { return fit(up(up(perform(op)))); }
  static <T> Comp<T, App<?>> ids(Ids<T> op) { return fit(up(up(up(perform(op))))); }
  static <T> Comp<T, App<?>> clock(Clock<T> op) { return fit(up(up(up(up(perform(op)))))); }
  static <T> Comp<T, App<?>> notice(Notify<T> op) { return fit(up(up(up(up(up(perform(op))))))); }
  static <T> Comp<T, App<?>> geocoder(Geocoder<T> op) { return fit(up(up(up(up(up(up(perform(op)))))))); }

  // ---- the programs: all of the one row

  static Comp<Optional<Boolean>, App<?>> inside(Conf c, Address a) {
    if (c.area().isEmpty()) return pure(Optional.of(false));
    Area area = c.area().get();
    if (area.cities().contains(a.city())) return pure(Optional.of(true));
    if (area.radiusKm() <= 0) return pure(Optional.of(false));
    return geocoder(new Geocoder.Find(a)).map(found ->
        found.map(g -> Booking.km(area.centre(), g) <= area.radiusKm()));
  }

  static Comp<Or<Plan>, App<?>> addressed(Conf c, Or<Plan> planned, Map<String, String> f) {
    if (!planned.ok() || !planned.value().atClient()) return pure(planned);
    Plan p = planned.value();
    Address a = new Address(f.getOrDefault("street", "").trim(), f.getOrDefault("postcode", "").trim(),
        f.getOrDefault("city", "").trim());
    if (a.street().isEmpty() || a.city().isEmpty() || !a.postcode().matches("[0-9]{2}-[0-9]{3}"))
      return pure(Or.refused("address"));
    return inside(c, a).map(in -> in.isEmpty() ? Or.<Plan>refused("area-unchecked")
        : in.get() ? Or.of(new Plan(p.offer(), true, Optional.of(a), p.price())) : Or.<Plan>refused("area"));
  }

  static Comp<Or<Booked>, App<?>> reserve(Conf c, Plan p, long at, String name, String contact, long now) {
    return together(t -> {
      Var<String> ref = t.ask(ids(new Ids.Ref(c.refPrefix() + "B")));
      Var<String> key = t.ask(ids(new Ids.Secret()));
      Var<Or<Booked>> made = ref.with(key, (r, k) -> new Booked(r, k, p.offer().key(), at, name, contact, now,
          p.address(), p.price())).then(b -> t.ask(store(new Store.Book(b, now))));
      return t.yield(made);
    });
  }

  static Comp<Void, App<?>> told(Conf c, Booked b) {
    return together(t -> {
      t.ask(mail(new Mail.ToClient(b.contact(), "booked " + b.ref())));
      t.ask(mail(new Mail.ToOwner("booked " + b.ref() + " by " + b.name())));
      t.ask(notice(new Notify.Tell("booked " + b.ref())));
      return t.done();
    });
  }

  static Comp<Response, App<?>> taken(Conf c, Plan p, long at, String name, String contact, long now) {
    return reserve(c, p, at, name, contact, now).flatMap(made ->
        !made.ok() ? pure(Booking.refused(made.refused(), 409))
            : told(c, made.value()).map(done -> new Response(200, "booked " + made.value().ref())));
  }

  static Comp<Response, App<?>> book(Conf c, Map<String, String> f, String ip) {
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
      return clock(new Clock.Now()).flatMap(now ->
          store(new Store.Allow("book:" + ip, 5, Booking.TEN_MINUTES, now)).flatMap(allowed ->
              !allowed ? pure(Booking.refused("limit", 429))
                  : captcha(new Captcha.Verify(f.getOrDefault("token", ""), ip)).flatMap(human ->
                      !human ? pure(Booking.refused("captcha", 403)) : taken(c, p, start, name, contact, now))));
    });
  }
}
