// A run that measured or photographed a page whose API calls 404'd must fail, not pass quietly.
//
// `server.mjs` answers 404 for a route it does not stub, and it always said so — on its own
// stdout, from a process the CI job starts in the background and nobody reads. Three routes
// lived there unnoticed: `/api/data-model/limits`, `/api/metrics/label-preview` and
// `/api/query/ddl-preview`.
//
// The cost is not only a screenshot of a page missing a panel. `layout-probe --check` walks
// those same pages and **fails a pull request** on `clipped`, `unreachable` and target budgets
// measured on content that was never rendered — a gate that does not measure what it claims to.
// So the question is asked at the end of every run, by all three consumers, through one helper
// rather than three copies: two copies of "did anything 404" is how one of them comes to stop
// asking.
//
// It is deliberately a *failure* and not a warning, which is the rule the probe already applies
// to its own states — a state that fails to open at a width it declares is a failure, never a
// silence, or a broken gesture is indistinguishable from one nobody measures.

/**
 * Fails the process when the stub server saw a route it does not serve.
 *
 * A server too old to answer `/__unstubbed` is reported and **not** treated as a pass: it is
 * exactly the case where nothing is known, and answering "nothing 404'd" to "I could not ask"
 * is the substitution this repository keeps removing.
 */
export async function failOnUnstubbedRoutes(baseUrl) {
  let routes;
  try {
    const res = await fetch(`${baseUrl}/__unstubbed`);
    if (!res.ok) throw new Error(`HTTP ${res.status}`);
    ({ routes } = await res.json());
  } catch (e) {
    console.error(`\nCould not ask ${baseUrl}/__unstubbed whether anything went unstubbed `
      + `(${String(e).split('\n')[0]}). Is server.mjs from this checkout?`);
    process.exit(1);
  }

  if (routes.length === 0) return;
  console.error(`\n${routes.length} API route(s) the SPA called and server.mjs does not stub:`);
  for (const route of routes) console.error(`  - ${route}`);
  console.error('\nEvery page that called one was rendered without it, so whatever this run '
    + 'produced describes an incomplete page. Add the stub to server.mjs (the response shape '
    + 'belongs in fixtures.mjs, taken from the controller rather than invented).');
  process.exit(1);
}
