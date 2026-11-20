# unused/

Files nothing references any more, kept rather than deleted so they can be restored with a
`git mv` back to the same path (the layout under this directory mirrors the repository root).

| File | Why it is here |
|---|---|
| `docs/SQL-BOUNDED-FLINK-STUDY.md` | A study of replacing the direct reader with a bounded Flink read; no document or check links to it. |
| `src/main/webapp/src/components/LoadingSpinner.tsx` | A component no page imports. |

Nothing under here is built, tested or checked: the SPA's `tsconfig` covers `src/` only.
Delete the directory once nobody needs these.
