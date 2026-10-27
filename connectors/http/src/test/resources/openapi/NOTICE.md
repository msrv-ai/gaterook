# Test fixtures of the OpenAPI import

- `billing-30.yaml` — written for MCP Gateway (report 10 §3.6): the traps of a real file in a small one.
- `billing-30.tools.json` — the golden of what `billing-30.yaml` compiles to (`OpenApiTemplateKindTest`).
- `petstore-expanded.yaml` and `tictactoe.yaml` — unchanged copies of the examples of
  [OAI/learn.openapis.org](https://github.com/OAI/learn.openapis.org) at commit
  `82b82f372aa185a07ea11bc158619895ab21f741` (`examples/v3.0/petstore-expanded.yaml`,
  `examples/v3.1/tictactoe.yaml`). The repository is licensed under
  [CC BY 4.0](https://creativecommons.org/licenses/by/4.0/), © OpenAPI Initiative; `petstore-expanded.yaml` names
  Apache 2.0 in its own `info.license`.

The large real descriptions (Stripe, GitHub) are not kept here: `-Popenapi-corpus` downloads them at pinned commits
into `~/.cache/mcp-gateway-test/openapi/` and checks their SHA-256 (`OpenApiCorpusIT`).
