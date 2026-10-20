/**
 * @file tests/unit/test_mic_render_queue.cpp
 * @brief Microphone frame preparation and successful render commit tests.
 */
#include <gtest/gtest.h>

#include "src/platform/windows/mic_render_queue.h"

using platf::audio::mic_render_queue_t;

TEST(MicRenderQueueTests, PreparingFailedSubmissionKeepsAudioForRetry) {
  mic_render_queue_t queue;
  const float samples[] {0.25f, -0.75f, 0.5f};
  queue.append(samples, 3, 19);

  float first_attempt[6] {};
  const auto failed = queue.submit_stereo(first_attempt, 3, []() { return false; });
  EXPECT_FALSE(failed.has_value());
  // A failed WASAPI ReleaseBuffer does not commit. Retrying must prepare the
  // exact same PCM, and neither preparation produces a rendered packet.
  EXPECT_EQ(queue.size(), 3);
  float retry[6] {};
  const auto rendered = queue.submit_stereo(retry, 3, []() { return true; });
  for (int frame = 0; frame < 3; ++frame) {
    EXPECT_FLOAT_EQ(first_attempt[frame * 2], samples[frame]);
    EXPECT_FLOAT_EQ(first_attempt[frame * 2 + 1], samples[frame]);
    EXPECT_FLOAT_EQ(retry[frame * 2], first_attempt[frame * 2]);
    EXPECT_FLOAT_EQ(retry[frame * 2 + 1], first_attempt[frame * 2 + 1]);
  }

  ASSERT_TRUE(rendered.has_value());
  ASSERT_EQ(rendered->size(), 1);
  EXPECT_EQ(rendered->front().sequence_number, 19);
  EXPECT_FLOAT_EQ(rendered->front().peak, 0.75f);
  EXPECT_EQ(queue.size(), 0);
  EXPECT_TRUE(queue.commit(3).empty());
}

TEST(MicRenderQueueTests, PacketSplitAcrossBuffersIsCountedOnlyAfterFinalCommit) {
  mic_render_queue_t queue;
  const float first[] {0.25f, -0.5f, 0.75f};
  const float second[] {0.125f, -0.25f};
  queue.append(first, 3, 65535);
  queue.append(second, 2, 0);

  EXPECT_TRUE(queue.commit(2).empty());
  EXPECT_EQ(queue.size(), 3);
  float stereo[6] {};
  queue.copy_stereo(stereo, 3);
  EXPECT_FLOAT_EQ(stereo[0], 0.75f);
  EXPECT_FLOAT_EQ(stereo[1], 0.75f);
  EXPECT_FLOAT_EQ(stereo[2], 0.125f);
  EXPECT_FLOAT_EQ(stereo[4], -0.25f);

  const auto rendered = queue.commit(3);
  ASSERT_EQ(rendered.size(), 2);
  EXPECT_EQ(rendered[0].sequence_number, 65535);
  EXPECT_FLOAT_EQ(rendered[0].peak, 0.75f);
  EXPECT_EQ(rendered[1].sequence_number, 0);
  EXPECT_FLOAT_EQ(rendered[1].peak, 0.25f);
  EXPECT_EQ(queue.size(), 0);
}

TEST(MicRenderQueueTests, LatencyTrimDoesNotReportDiscardedPacketAsRendered) {
  mic_render_queue_t queue;
  const float first[] {0.9f, 0.4f, 0.1f};
  const float second[] {-0.5f, 0.25f};
  queue.append(first, 3, 41);
  queue.append(second, 2, 42);
  queue.trim_to(3);

  float stereo[6] {};
  queue.copy_stereo(stereo, 3);
  EXPECT_FLOAT_EQ(stereo[0], 0.1f);
  EXPECT_FLOAT_EQ(stereo[2], -0.5f);
  const auto rendered = queue.commit(3);
  ASSERT_EQ(rendered.size(), 1);
  EXPECT_EQ(rendered.front().sequence_number, 42);
  EXPECT_EQ(queue.size(), 0);
}

TEST(MicRenderQueueTests, ClampAndClearMatchSubmittedSamples) {
  mic_render_queue_t queue;
  const float samples[] {1.5f, -2.0f};
  EXPECT_FLOAT_EQ(queue.append(samples, 2, 7), 1.0f);
  float stereo[4] {};
  queue.copy_stereo(stereo, 2);
  EXPECT_FLOAT_EQ(stereo[0], 1.0f);
  EXPECT_FLOAT_EQ(stereo[3], -1.0f);
  queue.clear();
  EXPECT_EQ(queue.size(), 0);
  EXPECT_TRUE(queue.commit(2).empty());
}
