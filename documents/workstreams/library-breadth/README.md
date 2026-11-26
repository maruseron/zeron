# Library Breadth

| Field | Value |
| --- | --- |
| Status | Complete |
| Released | `0.9.0` |
| ZEPs | [ZEP-0066](../../zeps/ZEP-0066.md), [ZEP-0067](../../zeps/ZEP-0067.md), [ZEP-0152](../../zeps/ZEP-0152.md), [ZEP-0153](../../zeps/ZEP-0153.md), [ZEP-0154](../../zeps/ZEP-0154.md), [ZEP-0155](../../zeps/ZEP-0155.md), [ZEP-0156](../../zeps/ZEP-0156.md), [ZEP-0157](../../zeps/ZEP-0157.md) |

## Mission

Add commonly needed standard-library capabilities without widening compiler or runtime semantics.
Library parts are additive, independently useful, and validated against the existing build and API
index.

## Parts

| Part | ZEP | Scope | Depends on | Status | Shipped |
| --- | --- | --- | --- | --- | --- |
| 1 | [ZEP-0066](../../zeps/ZEP-0066.md) | TOML parser | — | Shipped | `0.9.0` |
| 2 | [ZEP-0067](../../zeps/ZEP-0067.md) | XML parser | — | Shipped | `0.9.0` |
| 3 | [ZEP-0152](../../zeps/ZEP-0152.md) | Random generation and shuffling | — | Shipped | `0.9.0` |
| 4 | [ZEP-0153](../../zeps/ZEP-0153.md) | Descriptive statistics | — | Shipped | `0.9.0` |
| 5 | [ZEP-0154](../../zeps/ZEP-0154.md) | Ordered collections | — | Shipped | `0.9.0` |
| 6 | [ZEP-0155](../../zeps/ZEP-0155.md) | Binary buffers and encodings | — | Shipped | `0.9.0` |
| 7 | [ZEP-0156](../../zeps/ZEP-0156.md) | Text formatting and regular expressions | — | Shipped | `0.9.0` |
| 8 | [ZEP-0157](../../zeps/ZEP-0157.md) | Collection building APIs | — | Shipped | `0.9.0` |

The parts were independent and shipped together to avoid repeated release overhead. Later library
additions are not automatically part of this completed workstream; record a new concern or a new
part when they become active.
