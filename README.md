# Plain Sight v0.7.2 — stricter personal-object verification

Fixes false identifications where the trained black key fob was confused with a mouse, controller, or microphone.

- **Identity confirmation:** a generic object candidate is never shown as a match for a saved personal item unless its distinctive local feature geometry matches the saved reference images. Matches must agree across multiple training views (or have much stronger evidence from one view).
- **Buttons and trim:** references now preserve higher-resolution details, including control buttons and trim, with higher JPEG quality. New 24-view captures use these settings; old references remain readable.
- **Search coverage:** saved-item searches inspect overlapping crops of the entire scene even when the universal detector identifies a convincing but incorrect object early.
- **Honest misses:** when saved identity cannot be confirmed, Plain Sight says it was not verified instead of labeling an unrelated black object as your key fob.
- The working **offline OCR**, universal object categories, and accelerated **live zoom tracking** are retained.

A trained item uses a multi-view image collection, not a true 3D geometric reconstruction. A 3D mesh would not restore details that are too small, blurred, or hidden in the live camera image. Especially distant, reflective and low-texture key fobs can still produce missed detections. All matching remains offline.

**Testing status:** GitHub Actions validates compilation. The exact key-fob recognition and speed need testing with the owner's actual objects, which are not available to this build environment.
