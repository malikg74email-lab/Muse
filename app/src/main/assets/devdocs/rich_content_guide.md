<!-- devdoc: internal dev doc, not shown to users, LLM queries via knowledge_search -->
# Complete Guide to Rich Content Rendering

> Trigger scenario: When the user asks "How do I send an image?", "Can an image have its text recognised?", "How are code/formulas in AI replies displayed?", "Can I send a video?" or "How do I send an attachment?", refer to this document and answer truthfully.
> This document is based on the real implementation in the source code: the Markdown rendering components in ui/chat/, the rich-content handling in MessageBubble, and AttachmentChip.kt.

## I. Overview

Shaheen 2 chat supports a wealth of rich content:
- **AI output side**: Full Markdown rendering (code highlighting/LaTeX formulas/tables/blockquotes/lists/images/videos)
- **User input side**: Images (compression/OCR), videos, file attachments, and smart handling of pasted content

## II. Scope of Markdown Rendering Support

| Type | Supported | Notes |
|---|---|---|
| Code block | ✅ | Syntax highlighting (CodeHighlighter), supports common languages |
| Inline code | ✅ | `code` |
| LaTeX formula | ✅ | $...$ inline / $$...$$ block-level (FormulaView) |
| Table | ✅ | GFM tables |
| Blockquote | ✅ | > quote |
| List | ✅ | Ordered/unordered/nested |
| Heading | ✅ | # ~ ###### |
| Bold/italic/strikethrough | ✅ | **bold** *italic* ~~strike~~ |
| Link | ✅ | [text](url), tap to open |
| Image | ✅ | ![alt](url), streaming inline rendering |
| Task list | ✅ | - [x] Completed item |
| Horizontal rule | ✅ | --- |

Note: Some HTML tags embedded in Markdown have only limited support; mathematical formulas rely on a KaTeX-like implementation, and a formula that is too complex may fail to render (the original text is retained).

## III. Image Capabilities

### 3.1 The User Sends an Image
- Entry point: The + button in the input bar → Image / Camera
- **Compression**: Automatically compressed before sending, reducing data usage and memory
- **OCR**: Images automatically go through ML Kit offline text recognition (Chinese + English), and the recognition result can be provided to the LLM as context
- Multiple images: Multiple images can be sent at once (limit of 4, with a sending limit of ≤2MB per image)

### 3.2 Images in AI Replies
- Streaming rendering of data URIs / network images
- **SmartImage decoding**: First probe the dimensions with inJustDecodeBounds → downsample with inSampleSize to prevent OOM → explicitly recycle() after display
- Tap an image to view it enlarged

## IV. Video Capabilities

- The user can send a local video file
- AI-generated video (the generate_video tool) plays inline in the message
- The player supports progress/full screen (via the system player or an embedded one)

## V. File Attachments

- The user can attach any file (AttachmentChip shows the file name/size/type)
- Attachments are referenced by URI and do not take up message text
- The AI can handle files via file_download / save_to_downloads
- Long pasted text is automatically detected as a file (>5000 characters) and prompts the user to convert it to an attachment

## VI. Rich-Content Boundaries and Limits

- A single image in base64 is ≤2MB; if it exceeds the limit it is discarded and a notice is shown
- At most 4 images at once
- Web-scraped content is truncated beyond 50,000 characters
- Overly long AI messages are automatically collapsed
- Audio: TTS read-aloud; recording files are not played inline

## VII. Frequently Asked Questions Q&A

1. **"Once I send an image, can the AI see it"**: Yes; after compression the image goes through the multimodal channel, and a text-only model automatically falls back to an OCR text description.
2. **"Why does a formula in an AI reply display as garbled text"**: Complex LaTeX may fail to render and will be shown as the original text; you can ask the AI to use a simpler notation.
3. **"How do I send a large file"**: Send it as an attachment; for a very large file, it is recommended to put it on a cloud drive first and provide a link.
4. **"Can I send a video to the AI for analysis"**: Sending a video file is supported, but what the AI analyses is the file name/metadata (when it has no video-understanding capability); the visual content requires a screenshot.
5. **"Will image compression make it blurry"**: Compression keeps a guaranteed minimum level of clarity, which is sufficient for general scenarios; the original image is retained in the photo album.

## VIII. Key Points for LLM Calls

- Markdown (code/tables/formulas/lists) can be used naturally in replies; the UI will render it correctly
- Use generate_image to generate images and generate_video for videos; the results are inlined automatically
- When referencing an image from the user, describe its content rather than pretending to have seen the original image (in the text-model scenario)
- Structure long content with headings/lists; avoid one large block of unformatted text
