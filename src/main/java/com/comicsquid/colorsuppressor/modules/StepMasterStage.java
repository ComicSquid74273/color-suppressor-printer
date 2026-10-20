package com.comicsquid.colorsuppressor.modules;

enum StepMasterStage {
    IDLE,
    FETCH_MAP,
    FILL_MAP,
    WAIT_FILLED_MAP,
    WAIT_BATCH_BUILD,
    BATCH_WALK_A,
    BATCH_WALK_B,
    WAIT_BREAK_2,
    WAIT_BREAK_ALL,
    WAIT_CLOSING_BUILD,
    CLOSING_WALK,
    WAIT_CLOSING_BREAK,
    LOCK_WALK,
    LOCKING,
    READY_TO_LOCK,
    ABORTED
}
