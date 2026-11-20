# SPDX-License-Identifier: AGPL-3.0-or-later
# Copyright (C) 2026 Kafka Explorer Contributors
#
# The two signals the forecast demo lives on, as pure functions of the clock.
#
# Both the live generator (live.sh) and the history seeder (seed.sh) load this file, so what
# was seeded for the past and what is produced from now on are one curve — the forecast then
# starts from a history that continues rather than one that jumps at the join. The functions
# depend on the wall clock alone (epoch seconds), never on when the demo was started: a stack
# restarted at any hour resumes the same curve, and so does the seeder.
#
# Both are a number of records waiting between two topics (produced minus consumed), which is
# the one thing a forecastable metric can be made of *and* a generator can hold exactly: the
# difference of two topics' end offsets is a count, with no timestamp to back-date and no
# window to blur it. (A latency or a consumer delay is recorded as a limited-scope measurement
# and never forecast, by design — see ForecastEligibility and MetricObservationJournal.)
#
#   unshipped(t)   Orders taken and not yet shipped: the warehouse falls behind for three hours
#                  and a bulk shipment drains it — a ramp from ~20 to ~450 records that drops back
#                  at the end of each three-hour cycle, with a small hourly ripple. It crosses the
#                  300 warning about two hours and twenty minutes into the cycle and the 400
#                  critical shortly before the end, so for a half hour before each the forecast
#                  says that it will, which is the point of a forecast.
#   backlog(t)     Requests waiting between two services: a strict hourly cycle, 10 to 130. It
#                  crosses the 110 warning near each peak and never the 135 critical — a cycle a
#                  forecast should *follow*, not extrapolate.
#
# Hand-written awk: it runs in the Kafka and PostgreSQL images as they are, with nothing to install.

function pi() { return 3.14159265358979 }

function unshipped(t,    m, phase) {
  m = t / 60
  phase = (m % 180) / 180
  if (phase < 0) phase += 1
  return 20 + 420 * (phase ^ 1.6) + 12 * sin(2 * pi() * m / 60)
}

function backlog(t) {
  return 70 + 60 * sin(2 * pi() * (t / 60) / 60)
}
