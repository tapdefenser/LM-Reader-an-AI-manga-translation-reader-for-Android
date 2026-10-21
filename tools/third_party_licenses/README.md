# Native ASR third-party notices

Pinned by `tools/asr_sources.lock.json` and fetched explicitly by
`tools/fetch_asr_sources.py`. These archives contain headers/source only; no model
weights and no network access happens at CMake configure time.

| Component | Revision | Archive SHA-256 |
| --- | --- | --- |
| whisper.cpp v1.9.5 | `d1be6fde11ac6e0407606b4e42fe72d34add8037` | `645445da884df6dbd52b30f82d7be7c01d1767c938714e477e786b88d529e9f4` |
| Vulkan-Headers `vulkan-sdk-1.3.275.0` | `217e93c664ec6704ec2d8c36fa116c1a4a1e2d40` | `fcd2136a9feb0402820b334d8242773462cc47ed397aa20c8f4d04f7ea18d810` |
| SPIRV-Headers `vulkan-sdk-1.3.275.0` | `1c6bb2743599e6eb6f37b2969acc0aef812e32e3` | `d46b261f1fbc5e85022cb2fada9a6facb5b0c9932b45007a77fe05639a605bd1` |

whisper.cpp remains MIT-licensed; its notice is copied to the release asset path by
the fetch script. Vulkan-Headers is Apache-2.0/MIT and SPIRV-Headers is MIT; the
verbatim notice files live next to this README.
