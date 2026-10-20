#include <gtest/gtest.h>

#include "src/microphone_owner.h"

#include <string>
#include <vector>

namespace {
  struct fake_session_t { bool microphone_enabled = true; };
  using microphone_owner::claim_result_e;
  using microphone_owner::identity_t;

  class MicrophoneOwnerTests : public ::testing::Test {
  protected:
    microphone_owner::state_t<fake_session_t> owner;
    fake_session_t first, second, third;
    std::vector<std::string> operations;
    bool backend_ready = false;
    bool init_succeeds = true;

    claim_result_e claim(fake_session_t *session, identity_t identity) {
      return owner.claim(session, identity, [&](fake_session_t *previous) {
        EXPECT_EQ(previous, owner.get());
        previous->microphone_enabled = false;
        operations.emplace_back("revoke");
      }, [&] {
        EXPECT_EQ(nullptr, owner.get());
        backend_ready = false;
        operations.emplace_back("release");
      }, [&] {
        EXPECT_EQ(nullptr, owner.get());
        operations.emplace_back("init");
        backend_ready = init_succeeds;
        return init_succeeds;
      });
    }

    bool release(fake_session_t *session, std::uint32_t id) {
      return owner.release(session, id, [&] {
        EXPECT_EQ(nullptr, owner.get());
        backend_ready = false;
        operations.emplace_back("release");
      });
    }

    void acquire_first(identity_t identity = {"paired-client-a", 10, true, true}) {
      ASSERT_EQ(claim_result_e::acquired, claim(&first, identity));
      ASSERT_EQ(&first, owner.get());
      ASSERT_TRUE(backend_ready);
      operations.clear();
    }
  };
}

TEST_F(MicrophoneOwnerTests, SameAuthenticatedClientGetsImmediateNewDecoderInRevokeReleaseInitOrder) {
  acquire_first();
  EXPECT_EQ(claim_result_e::handed_off, claim(&second, {"paired-client-a", 11, true, true}));
  EXPECT_EQ((std::vector<std::string> {"revoke", "release", "init"}), operations);
  EXPECT_FALSE(first.microphone_enabled);
  EXPECT_EQ(&second, owner.get());
  EXPECT_TRUE(backend_ready);
}

TEST_F(MicrophoneOwnerTests, OldSessionJoinCannotReleaseReplacementBackend) {
  acquire_first();
  ASSERT_EQ(claim_result_e::handed_off, claim(&second, {"paired-client-a", 11, true, true}));
  operations.clear();
  EXPECT_FALSE(release(&first, 10));
  EXPECT_TRUE(operations.empty());
  EXPECT_TRUE(backend_ready);
  EXPECT_EQ(&second, owner.get());
  EXPECT_TRUE(release(&second, 11));
  EXPECT_EQ((std::vector<std::string> {"release"}), operations);
  EXPECT_EQ(nullptr, owner.get());
  EXPECT_FALSE(backend_ready);
  EXPECT_FALSE(release(&second, 11));
}

TEST_F(MicrophoneOwnerTests, DifferentPairedClientCannotStealMicrophone) {
  acquire_first();
  EXPECT_EQ(claim_result_e::denied, claim(&second, {"paired-client-b", 11, true, true}));
  EXPECT_EQ(&first, owner.get());
  EXPECT_TRUE(first.microphone_enabled);
  EXPECT_TRUE(operations.empty());
}

TEST_F(MicrophoneOwnerTests, EmptyIdentityOnEitherSideCannotAuthorizeHandoff) {
  acquire_first();
  EXPECT_EQ(claim_result_e::denied, claim(&second, {"", 11, true, true}));
  ASSERT_TRUE(release(&first, 10));
  acquire_first({"", 12, true, true});
  EXPECT_EQ(claim_result_e::denied, claim(&second, {"", 13, true, true}));
  EXPECT_EQ(claim_result_e::denied, claim(&second, {"paired-client-a", 13, true, true}));
  EXPECT_TRUE(operations.empty());
}

TEST_F(MicrophoneOwnerTests, ReplayedOrOlderLaunchCannotDisplaceNewerOwner) {
  acquire_first();
  for (auto id : {0u, 9u, 10u}) {
    EXPECT_EQ(claim_result_e::denied, claim(&second, {"paired-client-a", id, true, true}));
  }
  EXPECT_EQ(&first, owner.get());
  EXPECT_TRUE(operations.empty());
}

TEST_F(MicrophoneOwnerTests, UnencryptedReplacementCannotTakeOver) {
  acquire_first();
  EXPECT_EQ(claim_result_e::denied, claim(&second, {"paired-client-a", 11, false, true}));
  EXPECT_TRUE(operations.empty());
}

TEST_F(MicrophoneOwnerTests, LegacyUnencryptedOwnerRequiresNormalRelease) {
  acquire_first({"paired-client-a", 10, false, true});
  EXPECT_EQ(claim_result_e::denied, claim(&second, {"paired-client-a", 11, true, true}));
  EXPECT_TRUE(operations.empty());
}

TEST_F(MicrophoneOwnerTests, NonViewingReplacementOrOwnerCannotAuthorizeHandoff) {
  acquire_first();
  EXPECT_EQ(claim_result_e::denied, claim(&second, {"paired-client-a", 11, true, false}));
  ASSERT_TRUE(release(&first, 10));
  acquire_first({"paired-client-a", 12, true, false});
  EXPECT_EQ(claim_result_e::denied, claim(&second, {"paired-client-a", 13, true, true}));
  EXPECT_TRUE(operations.empty());
}

TEST_F(MicrophoneOwnerTests, DuplicateClaimAndWrongGenerationReleaseAreHarmless) {
  acquire_first();
  EXPECT_EQ(claim_result_e::denied, claim(&first, {"paired-client-a", 11, true, true}));
  EXPECT_EQ(claim_result_e::denied, claim(nullptr, {"paired-client-a", 11, true, true}));
  EXPECT_FALSE(release(&first, 11));
  EXPECT_TRUE(operations.empty());
  EXPECT_EQ(&first, owner.get());
}

TEST_F(MicrophoneOwnerTests, FailedNewBackendLeavesNoOwnerAndStaleJoinCannotCloseAFutureBackend) {
  acquire_first();
  init_succeeds = false;
  EXPECT_EQ(claim_result_e::backend_failure, claim(&second, {"paired-client-a", 11, true, true}));
  EXPECT_EQ(nullptr, owner.get());
  EXPECT_FALSE(backend_ready);
  EXPECT_FALSE(first.microphone_enabled);
  operations.clear();
  EXPECT_FALSE(release(&first, 10));
  EXPECT_FALSE(release(&second, 11));
  EXPECT_TRUE(operations.empty());
  init_succeeds = true;
  EXPECT_EQ(claim_result_e::acquired, claim(&third, {"paired-client-a", 12, true, true}));
  EXPECT_FALSE(release(&first, 10));
  EXPECT_FALSE(release(&second, 11));
  EXPECT_EQ(&third, owner.get());
  EXPECT_TRUE(backend_ready);
}

TEST_F(MicrophoneOwnerTests, FailedInitialBackendPublishesNoOwner) {
  init_succeeds = false;
  EXPECT_EQ(claim_result_e::backend_failure, claim(&first, {"paired-client-a", 10, true, true}));
  EXPECT_EQ(nullptr, owner.get());
  EXPECT_FALSE(release(&first, 10));
}

TEST_F(MicrophoneOwnerTests, MultipleRapidHandoffsRejectEverySupersededGeneration) {
  acquire_first();
  ASSERT_EQ(claim_result_e::handed_off, claim(&second, {"paired-client-a", 11, true, true}));
  ASSERT_EQ(claim_result_e::handed_off, claim(&third, {"paired-client-a", 12, true, true}));
  EXPECT_FALSE(release(&first, 10));
  EXPECT_FALSE(release(&second, 11));
  EXPECT_EQ(claim_result_e::denied, claim(&second, {"paired-client-a", 11, true, true}));
  EXPECT_EQ(&third, owner.get());
  EXPECT_TRUE(backend_ready);
  EXPECT_TRUE(release(&third, 12));
}
