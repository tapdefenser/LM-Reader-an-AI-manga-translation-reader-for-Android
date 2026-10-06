# Cat-paw workflows

[简体中文](WORKFLOWS.zh-CN.md) · [User guide](USAGE.en.md)

Open **Main menu → Translation workflows**. Start with the built-in local translation workflow, or copy a standard text, direct bubble vision or direct page vision reference template. Built-in templates are read-only; copy them before editing. Select the resulting workflow in the comic's translation options. The full-comic fast translation template has been removed from built-in and editor choices; saved user copies and queued snapshots remain executable.

## Structure and editing

Each workflow has a comic scope, one chapter loop and one page loop. New workflows start with a Seg step in the page loop. Add ordinary steps at a scope's end; select a card to open its parameter drawer. The three-dot menu moves, copies or deletes a step. Moves are checked against scope/type rules and cannot move a step into itself. Undo retains the last 50 structure edits.

```text
Comic
  Each chapter [sync / async]
    Each page [sync / async]
      Seg → page bubbles
      Each bubble
        OCR → source text
        Translate / API → translated text
```

The top menu provides names/retry settings, templates, unified API binding, generated Cat-paw text and instructions. The generated text reflects the actual tree and variable bindings. Changing a parameter with unsaved edits prompts you to save, discard or continue editing.

Each manga's **Translation options → SEG text scope** offers bubbles, free text, or both (the default). The selection is saved per manga and captured in new task snapshots. Existing queued tasks keep their snapshot. The Seg step still accepts only an image; it has no scope parameter.

Seg detects text lines in all three scopes and adds captions outside balloons that the bubble model missed. Both kinds are returned in one `<List<Bubble>>`; each item includes its own identity, crop and region kind. The same item loop handles both. Vision API workflows without an OCR step also load the text detector. OCR reuses Seg's line boxes and only recognizes text. Detection is part of the Seg function, while its resources, concurrency and acceleration settings remain shared with OCR. Bubble segmentation keeps its Seg concurrency and GPU settings. Detector models remain loaded when Seg is needed; recognition models are only loaded for OCR steps.

Every successful SEG run rebuilds the page's bubbles: it replaces the page list, clears stale page translation records, OCR coordinates and crop caches, and refreshes the reading preview. A custom output variable also synchronizes the page; empty detections leave an empty list. New detections receive fresh identities, so old crop references cannot be used by OCR or API steps. Keep SEG outside bubble loops to avoid rebuilding a list while it is being iterated.

Text blocks are assigned to balloon contours before filtering the scope. Connected balloons retain separate text targets and significant mask components; overlapping balloon boxes no longer suppress each other solely by containment. Local OCR and API image attachments use the same isolated crops. OCR joins layout line breaks within each region using the source language's spacing rules before passing text to translation steps.

## Steps and variables

| Step | Use |
|---|---|
| Seg / OCR / local translation | Detect regions using the manga's text scope, recognize text and run installed offline models |
| API / streaming API | Send prompts/context and optional images; validate typed output |
| Fill translated bubbles | Map ordered output back to existing bubble identities |
| Each item / If | Iterate a list/dictionary or choose a conditional branch |
| Append / merge / match-replace | Build text/lists, append to an outer list or text from any nested row, and apply glossary substitutions |
| Add context / add glossary names | Construct few-shot messages and add missing comic names |

The `{}` control lists variables available in the current scope. Types appear in angle brackets throughout the editor and generated Cat Paw text, for example `<Text> · Source language` and `<List<Bubble>> · Bubble list`. Inputs filter by type; outputs also filter out read-only fields, except for Append and merge, which may target an outer list or text even from an async branch. Prompts insert stable variable references through the variable picker. Renaming a variable preserves its identity; manually typed unbound placeholders fail validation. Images are sent as actual API attachments. Requests inside a bubble loop automatically attach the current item crop when no attachment is selected. Choose another image or **No image** to override this; an explicit empty image list also disables automatic attachment. Requests outside bubble loops have no automatic image.

Both ordinary and streaming API steps accept `<Image>` or `<List<Image>>` attachments. Create an image-list variable directly, then populate it with Append or loop collection. A list may be declared on an outer row and filled from a nested loop: declare the image list on the page, append the current bubble crop with Append inside the bubble loop, and send the finished list after the loop. Selecting the list sends all images in list order in the same request and overrides automatic attachment of the current bubble. The number of images per request is unlimited; the combined Base64 size (16 MB per request) is the only budget, so split larger sets into batches. An empty list sends no images.

Comic variables include source/target languages, resolved style and the shared glossary. Chapter/page/item variables belong to their loops. A custom variable is available after its declaration to the end of its branch. Asynchronous child branches read parent variables without rewriting them, but Append and merge may add to an outer list or text: appends are serialized on the owning frame, so parallel branches never lose items even though their order is unspecified. Use collection outputs when the results must follow the input order.

Each input, output and prompt variable bar has its own search field for variable names, fields and types. Type selection also supports search. Filtering preserves the selected reference.

Every list offers a read-only `<Number> · List name · Item count` reference for its current length, including empty lists (0), nested lists and lists in records. Use it as a numeric input or insert it into a template. Assignment (`=`) and Append (`+=`) show primitive value choices before variable references: select `<Number> value` or `<Text> value` and enter a literal number or string. Numbers accept negative values and decimals; inputs must match the output variable or list element type.

The direct page vision template collects every SEG crop in order and sends them in one streaming vision request per page. Each result contains only `source` and `translation`; the stream index determines its target bubble. Crops with no readable text still return an empty pair. The image-list count validates the complete response, so missing or extra results fail before page publication. Empty pages skip the API. This template uses no local OCR, local translation or chapter glossary extraction; existing glossary names remain in the prompt. An [importable template file](templates/vl-page.lmworkflow.json) is also available.

The direct VL template sends one current bubble crop per request and expects one JSON object with `source` and `translation`, without `bubbleId`. Text blocks within that bubble are joined in those fields. Concurrent iterations have separate local variables, execute their steps in order, and all finish before the next outer step. Batch backfill still validates bubble IDs. Saved workflows and queued snapshots retain their existing programs; update their prompts and output types together.

Actual attachments receive an additional image-reading hint at execution time. Single-region `source` / `translation` requests explicitly return empty strings when a candidate contains no readable text; this also applies to saved vision workflows. Chat requests with record or list output automatically derive JSON Schema from the selected type, unless the API configuration already supplies `response_format`. An explicit HTTP 400/422 rejection of structured output before any response text causes one fallback request without the automatic parameter. Both attempts are logged. Other protocols retain prompt guidance and local validation. Explanations or example JSON in model replies are never extracted as translations.

**Main menu → API logs** includes reader retranslations under the manga, with a **Single-page retranslation** step prefix. JSON, structure and identity validation failures mark the corresponding request as failed even after HTTP 200, preserving the status and original model response. Errors include the actual attachment count. Logs store image MIME types and approximate sizes without Base64 or API keys.

## Concurrency and API binding

Sync/async loop controls affect scheduling. Actual requests remain subject to engine/API concurrency limits; a queue waiting for a permit is not an active request. The queue displays actual Seg/OCR/API activity and omits the current step. Text detection counts as OCR activity.

The parameter sheet's "Maximum parallelism" applies to async modules only: Auto follows the engine limit, and 1-8 caps how many items this module works on at once without exceeding the engine limit. Sync modules hide the parameter but keep any saved value.

Copied/imported templates must bind API references to configurations on this device. Use the unified binding action or choose a configuration in each request step. Exported workflows do not transfer API keys. Image API steps may send page/bubble images to the configured provider; inspect prompts and logs before sharing.

The glossary is shared by the comic and read at execution time. Added names fill missing original terms without replacing existing entries. Match-replace uses the longest matching original term and does not recursively match replacements.

## Saving and recovery

Queued jobs use a fixed workflow snapshot. A streaming step can fill validated list entries progressively; later steps wait for the full response to finish and validate. Persisted page translations and edits survive restarts, while arbitrary workflow variables and intermediate steps do not resume as checkpoints.

Foreground task notifications support background work, pause/cancel and access to queues. Process termination requires manual retry of interrupted work. See [backup and task recovery](BACKUP.en.md) for persistence and export boundaries.

Import/export carries structure, stable identifiers and API references. Verify bindings and available offline models before running an imported workflow. If a workflow is referenced by comics, deletion lists those references; already queued jobs retain their snapshots.
