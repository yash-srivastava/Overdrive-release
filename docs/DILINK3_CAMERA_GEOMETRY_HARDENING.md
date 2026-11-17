# DiLink 3 panoramic geometry: provenance and compatibility

The legacy camera resolver historically stored `probedWidth` and `probedHeight`
when selecting a vehicle profile, even though those dimensions were **profile
assumptions**, not measurements from the camera. New profile selections no
longer write those fields. The resolver instead supplies the selected profile's
dimensions when saved geometry is missing or invalid.

For compatibility, existing **valid** saved dimension pairs are retained. The
resolver rejects partial, nonpositive, odd, or out-of-range dimensions as a
pair and logs that it fell back to profile dimensions. This check does not
establish that a saved pair was measured from the HAL; old values may have
been seeded by previous versions. In particular, this change does **not**
automatically reconfigure the ImageReader or encoder from observed frames.
That would risk breaking recordings on legacy hardware where the producer's
actual dimensions differ from the consumer's configured geometry.

`persistPanoramicProbe` already logs observed producer dimensions but deliberately
does not persist them as future pipeline geometry. Operators should compare
those diagnostics against the selected profile before changing camera geometry
manually. This patch does not open additional camera devices, change camera
IDs, or change the four-camera slice order.

## Verification boundary

Static source review and `git apply --check` can establish that the patch is
well-formed. Device-specific frame geometry, HAL capabilities, and recording
coexistence must be checked on real DiLink 3 hardware before treating any
source mapping or geometry as verified.
