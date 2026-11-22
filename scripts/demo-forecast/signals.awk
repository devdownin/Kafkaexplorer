# SPDX-License-Identifier: AGPL-3.0-or-later
# Copyright (C) 2026 Kafka Explorer Contributors
#
# The five signals the forecast demo lives on, as pure functions of the clock.
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
# Three more, each there to show something a forecast does *differently*, so the demo is not five
# variations on a ramp. All three are a record count held between two topics, like the first two.
#
#   creep(t)       Imports not yet indexed: a slow, steady climb with a little noise, 25 to 235 over four
#                  hours, then a reindex drains it. A trend with noise on it — the forecast extends the
#                  slope and the band widens with the distance. Warning 180, critical 220.
#   jitter(t)      Events not yet archived: nothing to see. Around 80 with a noise of +/- 30 and no cycle,
#                  never near its 150 warning. The forecast is flat and its band wide: the case that
#                  shows what an uncertain series looks like, and that a forecast is not always news.
#   shift(t)       Tickets open: a level that changes. 50 for ninety minutes, a ramp of six minutes, 130
#                  for seventy-eight, a ramp back: a three-hour cycle. No forecast can see a handover coming, so each one is a
#                  miss the card's reliability badge and the "previous forecast vs actual" view then show
#                  — a series that is honest about the limits. Warning 100, critical 140.
#
# The noise is a function of the minute and nothing else (`noise`), not a random number: the seeded
# past and the live future must read the same value at the same minute, as the curves do.
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

# A reproducible value in [-1, 1] for each minute, from the minute alone (the classic sine hash). The
# same minute gives the same value in the Kafka image's awk and in the PostgreSQL image's, and on any
# restart: the argument is exact in a double and the fractional part is taken after the scaling.
function noise(t,    m, x) {
  m = int(t / 60)
  x = sin(m * 12.9898 + 78.233) * 43758.5453
  x = x - int(x)
  if (x < 0) x += 1
  return 2 * x - 1
}

function creep(t,    m, phase) {
  m = t / 60
  phase = (m % 240) / 240
  if (phase < 0) phase += 1
  return 25 + 210 * phase + 8 * noise(t)
}

function jitter(t) {
  return 80 + 30 * noise(t)
}

function shift(t,    m, c, level) {
  m = t / 60
  c = m % 180
  if (c < 0) c += 180
  # Minutes into the three-hour cycle: 0-90 low, 90-96 ramp up, 96-174 high, 174-180 ramp down.
  if (c < 90)        level = 50
  else if (c < 96)   level = 50 + 80 * (c - 90) / 6
  else if (c < 174)  level = 130
  else               level = 130 - 80 * (c - 174) / 6
  return level + 5 * noise(t)
}
