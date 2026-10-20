#include <gtest/gtest.h>
#include "src/stream_activity.h"
#include <string>
#include <vector>

namespace {
  const std::string pause_packet("\x01\x01\x00\x00", 4);
  const std::string resume_packet("\x01\x00\x00\x00", 4);
  using stream_activity::command_result_e;
}

TEST(BackgroundStreamTests, WirePacketHasFixedVersionBooleanAndReservedBytes) {
  ASSERT_TRUE(stream_activity::parse_command(pause_packet).has_value());
  EXPECT_TRUE(*stream_activity::parse_command(pause_packet));
  ASSERT_TRUE(stream_activity::parse_command(resume_packet).has_value());
  EXPECT_FALSE(*stream_activity::parse_command(resume_packet));
  for (auto packet : {std::string(), pause_packet.substr(0, 3), pause_packet + "x",
       std::string("\x02\x01\x00\x00", 4), std::string("\x01\x02\x00\x00", 4),
       std::string("\x01\x01\x01\x00", 4), std::string("\x01\x01\x00\x01", 4)}) {
    EXPECT_FALSE(stream_activity::parse_command(packet).has_value());
  }
}

TEST(BackgroundStreamTests, UnauthenticatedOrStoppedOrNonViewerCannotPause) {
  auto state = std::make_shared<stream_activity::state_t>();
  int idrs = 0;
  for (const auto &flags : {std::vector<bool>{false, true, true}, {true, false, true}, {true, true, false}}) {
    EXPECT_EQ(stream_activity::apply_command(pause_packet, flags[0], flags[1], flags[2], state,
        [&] { idrs++; }), command_result_e::denied);
    EXPECT_FALSE(state->background());
  }
  EXPECT_EQ(idrs, 0);
}

TEST(BackgroundStreamTests, ResumeRequestsOneIdrAndDuplicateCommandsAreIdempotent) {
  auto state = std::make_shared<stream_activity::state_t>();
  int idrs = 0;
  auto apply = [&](const auto &packet) {
    return stream_activity::apply_command(packet, true, true, true, state, [&] { idrs++; });
  };
  EXPECT_EQ(apply(pause_packet), command_result_e::paused);
  EXPECT_TRUE(stream_activity::paused(state));
  EXPECT_EQ(apply(pause_packet), command_result_e::unchanged);
  EXPECT_EQ(idrs, 0);
  EXPECT_EQ(apply(resume_packet), command_result_e::resumed);
  EXPECT_FALSE(stream_activity::paused(state));
  EXPECT_EQ(idrs, 1);
  EXPECT_EQ(apply(resume_packet), command_result_e::unchanged);
  EXPECT_EQ(idrs, 1);
}

TEST(BackgroundStreamTests, MalformedPacketNeverChangesStateOrRequestsIdr) {
  auto state = std::make_shared<stream_activity::state_t>();
  state->set_background(true);
  int idrs = 0;
  EXPECT_EQ(stream_activity::apply_command("bad", true, true, true, state, [&] { idrs++; }),
      command_result_e::malformed);
  EXPECT_TRUE(state->background());
  EXPECT_EQ(idrs, 0);
}

TEST(BackgroundStreamTests, PausingOneClientNeverPausesOtherClientOrSharedCapture) {
  auto hidden = std::make_shared<stream_activity::state_t>();
  auto visible = std::make_shared<stream_activity::state_t>();
  std::vector<stream_activity::state_ptr> sessions {hidden, visible};
  stream_activity::apply_command(pause_packet, true, true, true, hidden, [] {});
  EXPECT_TRUE(stream_activity::paused(hidden));
  EXPECT_FALSE(stream_activity::paused(visible));
  EXPECT_FALSE(stream_activity::all_paused(sessions, stream_activity::paused));
  stream_activity::apply_command(pause_packet, true, true, true, visible, [] {});
  EXPECT_TRUE(stream_activity::all_paused(sessions, stream_activity::paused));
  stream_activity::apply_command(resume_packet, true, true, true, hidden, [] {});
  EXPECT_FALSE(stream_activity::all_paused(sessions, stream_activity::paused));
}

TEST(BackgroundStreamTests, CaptureStateOutlivesSessionAndEmptyGroupsAcceptNewSubscribers) {
  auto state = std::make_shared<stream_activity::state_t>();
  std::vector<stream_activity::state_ptr> capture_sessions {state};
  state->set_background(true);
  state.reset();
  EXPECT_TRUE(stream_activity::all_paused(capture_sessions, stream_activity::paused));
  capture_sessions.clear();
  EXPECT_FALSE(stream_activity::all_paused(capture_sessions, stream_activity::paused));
  capture_sessions.push_back(std::make_shared<stream_activity::state_t>());
  EXPECT_FALSE(stream_activity::all_paused(capture_sessions, stream_activity::paused));
}
