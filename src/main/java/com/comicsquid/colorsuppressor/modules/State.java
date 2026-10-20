package com.comicsquid.colorsuppressor.modules;

enum State {
    SelectingLowerMapArea,
    SelectingTable,
    SelectingFinishedMapChest,
    SelectingAnvil,
    SelectingFoodChest,
    SelectingXpChest,
    SelectingChests,
    AwaitRegisterResponse,
    AwaitRestockResponse,
    AwaitMapChestResponse,
    AwaitFoodChestResponse,
    AwaitDepositResponse,
    AwaitBreakPermission2,
    AwaitBreakPermissionAll,
    AwaitClosingBreak,
    StandBy,
    Walking,
    Mining,
}
