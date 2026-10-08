#ifndef GUIDECAST_RNNOISE_OS_SUPPORT_H
#define GUIDECAST_RNNOISE_OS_SUPPORT_H
#include <string.h>
/* RNNoise 0.2's ARM NEON header references only OPUS_CLEAR from an omitted Opus header.
 * Supply that bounded-array clearing operation without importing unrelated allocation hooks. */
#define OPUS_CLEAR(destination, count) memset((destination), 0, sizeof(*(destination)) * (size_t)(count))
#endif
