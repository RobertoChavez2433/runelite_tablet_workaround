/*
 * Host-compiled test for renderer wait-reason classification.
 * Compile: gcc -o RendererWaitReasonTest RendererWaitReasonTest.c && ./RendererWaitReasonTest
 *
 * Tests the classifyWaitReason() pure function extracted from renderer.c.
 */
#include <stdio.h>
#include <stdbool.h>
#include <stdlib.h>

/* Mirror the enum and function from renderer.c */
typedef enum {
    WAIT_NONE = 0,
    WAIT_CHOREOGRAPHER,
    WAIT_CONTENT,
    WAIT_STATE,
} WaitReason;

WaitReason classifyWaitReason(bool hasState, bool surfaceAvailable, bool waitForNextFrame,
                              bool waitingForBuffers, bool drawRequested, bool cursorMoved,
                              bool cursorUpdated, bool stateChanged, bool windowChanged,
                              bool buffersChanged) {
    if (stateChanged || windowChanged || buffersChanged)
        return WAIT_NONE;
    if (!hasState || !surfaceAvailable || waitingForBuffers)
        return WAIT_STATE;
    if (waitForNextFrame)
        return WAIT_CHOREOGRAPHER;
    if (!drawRequested && !cursorMoved && !cursorUpdated)
        return WAIT_CONTENT;
    return WAIT_NONE;
}

static int tests_run = 0;
static int tests_passed = 0;

#define ASSERT_EQ(expected, actual, msg) do { \
    tests_run++; \
    if ((expected) == (actual)) { tests_passed++; } \
    else { printf("FAIL: %s — expected %d, got %d\n", msg, (int)(expected), (int)(actual)); } \
} while(0)

int main(void) {
    /* State changes always return WAIT_NONE (process immediately) */
    ASSERT_EQ(WAIT_NONE, classifyWaitReason(true, true, true, false, false, false, false, true, false, false),
              "stateChanged overrides all");
    ASSERT_EQ(WAIT_NONE, classifyWaitReason(true, true, true, false, false, false, false, false, true, false),
              "windowChanged overrides all");
    ASSERT_EQ(WAIT_NONE, classifyWaitReason(true, true, true, false, false, false, false, false, false, true),
              "buffersChanged overrides all");

    /* No state → WAIT_STATE */
    ASSERT_EQ(WAIT_STATE, classifyWaitReason(false, false, false, false, false, false, false, false, false, false),
              "no state = WAIT_STATE");

    /* State but no surface → WAIT_STATE */
    ASSERT_EQ(WAIT_STATE, classifyWaitReason(true, false, false, false, false, false, false, false, false, false),
              "no surface = WAIT_STATE");

    /* Waiting for buffers → WAIT_STATE */
    ASSERT_EQ(WAIT_STATE, classifyWaitReason(true, true, false, true, false, false, false, false, false, false),
              "waitingForBuffers = WAIT_STATE");

    /* waitForNextFrame → WAIT_CHOREOGRAPHER */
    ASSERT_EQ(WAIT_CHOREOGRAPHER, classifyWaitReason(true, true, true, false, false, false, false, false, false, false),
              "waitForNextFrame = WAIT_CHOREOGRAPHER");

    /* No draw or cursor → WAIT_CONTENT */
    ASSERT_EQ(WAIT_CONTENT, classifyWaitReason(true, true, false, false, false, false, false, false, false, false),
              "no draw/cursor = WAIT_CONTENT");

    /* drawRequested → WAIT_NONE */
    ASSERT_EQ(WAIT_NONE, classifyWaitReason(true, true, false, false, true, false, false, false, false, false),
              "drawRequested = WAIT_NONE");

    /* cursorMoved → WAIT_NONE */
    ASSERT_EQ(WAIT_NONE, classifyWaitReason(true, true, false, false, false, true, false, false, false, false),
              "cursorMoved = WAIT_NONE");

    /* cursorUpdated → WAIT_NONE */
    ASSERT_EQ(WAIT_NONE, classifyWaitReason(true, true, false, false, false, false, true, false, false, false),
              "cursorUpdated = WAIT_NONE");

    /* Choreographer trumps content when both conditions met */
    ASSERT_EQ(WAIT_CHOREOGRAPHER, classifyWaitReason(true, true, true, false, false, false, false, false, false, false),
              "choreographer priority over content");

    /* State trumps choreographer */
    ASSERT_EQ(WAIT_STATE, classifyWaitReason(true, false, true, false, false, false, false, false, false, false),
              "state priority over choreographer");

    printf("%d/%d tests passed\n", tests_passed, tests_run);
    return tests_passed == tests_run ? 0 : 1;
}
