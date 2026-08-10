/*
 * Copying to the clipboard from the origins this application is actually served from.
 *
 * `navigator.clipboard` is secure-context only, exactly like `crypto.randomUUID`. The app
 * ships with no authentication and is documented as an internal tool, so the ordinary
 * deployment is plain HTTP on a hostname — where the property is `undefined` and
 * `navigator.clipboard.writeText(…)` throws `TypeError: Cannot read properties of
 * undefined`. Of the nine call sites this replaces, one caught the failure and told the
 * user; the other eight threw inside a click handler, so the button did nothing at all and
 * said nothing about it.
 *
 * The fallback is `document.execCommand('copy')` over a temporary textarea. It is
 * deprecated and it is also the only thing that works on an insecure origin, which is the
 * case that matters here. It requires a user gesture — every caller is a click handler.
 *
 * Returns whether the text was copied, so a caller can confirm what happened instead of
 * announcing a success it did not verify.
 */

export interface CopyDeps {
  clipboard?: Pick<Clipboard, 'writeText'>;
  doc?: Document;
}

export async function copyText(text: string, deps: CopyDeps = {}): Promise<boolean> {
  const clipboard = 'clipboard' in deps ? deps.clipboard : globalThis.navigator?.clipboard;
  const doc = deps.doc ?? globalThis.document;

  // Optional call, not a truthiness check: on an insecure origin the property is absent.
  try {
    if (clipboard?.writeText) {
      await clipboard.writeText(text);
      return true;
    }
  } catch {
    // Present but refused — a permissions policy, or a document without focus. Fall
    // through: the legacy path often still works.
  }

  return legacyCopy(text, doc);
}

function legacyCopy(text: string, doc: Document | undefined): boolean {
  if (!doc?.body) return false;
  let area: HTMLTextAreaElement | null = null;
  try {
    area = doc.createElement('textarea');
    area.value = text;
    // Off-screen rather than hidden: a `display: none` element cannot be selected, and
    // `readOnly` keeps a mobile keyboard from opening over the page.
    area.setAttribute('readonly', '');
    area.style.position = 'fixed';
    area.style.top = '-1000px';
    area.style.opacity = '0';
    doc.body.appendChild(area);
    area.select();
    area.setSelectionRange(0, text.length);
    return doc.execCommand('copy');
  } catch {
    return false;
  } finally {
    if (area?.parentNode) area.parentNode.removeChild(area);
  }
}
