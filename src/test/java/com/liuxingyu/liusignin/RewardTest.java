package com.liuxingyu.liusignin;

import com.liuxingyu.liusignin.reward.Reward;
import com.liuxingyu.liusignin.reward.RewardGroup;
import org.junit.jupiter.api.Test;

import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class RewardTest {

    @Test
    void parsesMoneyAndExp() {
        Reward money = Reward.parse("money:1000");
        assertEquals(Reward.Type.MONEY, money.type());
        assertEquals(1000.0, money.amount());

        Reward exp = Reward.parse("exp:50");
        assertEquals(Reward.Type.EXP, exp.type());
        assertEquals(50.0, exp.amount());

        Reward card = Reward.parse("card:2");
        assertEquals(Reward.Type.CARD, card.type());
    }

    @Test
    void parsesItemsKeepingSpec() {
        Reward item = Reward.parse("item:ce:myitems:flame_sword*3");
        assertEquals(Reward.Type.ITEM, item.type());
        assertEquals("ce:myitems:flame_sword*3", item.value());
    }

    @Test
    void parsesCommandModes() {
        Reward console = Reward.parse("cmd:say hello");
        assertEquals("console", console.mode());
        assertEquals("say hello", console.value());

        Reward explicitConsole = Reward.parse("cmd:console: give {player} diamond 1");
        assertEquals("console", explicitConsole.mode());
        assertEquals("give {player} diamond 1", explicitConsole.value());

        Reward player = Reward.parse("command:player: menu");
        assertEquals("player", player.mode());
        assertEquals("menu", player.value());
    }

    @Test
    void rejectsInvalidEntries() {
        assertThrows(IllegalArgumentException.class, () -> Reward.parse("diamond:3"));
        assertThrows(IllegalArgumentException.class, () -> Reward.parse("money:abc"));
        assertThrows(IllegalArgumentException.class, () -> Reward.parse("money:NaN"));
        assertThrows(IllegalArgumentException.class, () -> Reward.parse("exp:Infinity"));
        assertThrows(IllegalArgumentException.class, () -> Reward.parse("没有冒号"));
    }

    @Test
    void infiniteRewardsGrowAndCap() {
        RewardGroup.Infinite infinite = new RewardGroup.Infinite(
                100, 50, 200, 0, 0, 0,
                List.of(Reward.parse("card:1")),
                new TreeMap<>(Map.of(3, List.of(Reward.parse("item:DIAMOND*1")))));

        List<Reward> day1 = infinite.rewardsFor(1);
        assertEquals(100.0, day1.get(0).amount());
        assertEquals(1, day1.stream().filter(r -> r.type() == Reward.Type.CARD).count());
        assertEquals(0, day1.stream().filter(r -> r.type() == Reward.Type.ITEM).count());

        List<Reward> day2 = infinite.rewardsFor(2);
        assertEquals(150.0, day2.get(0).amount());

        List<Reward> day3 = infinite.rewardsFor(3);
        assertEquals(200.0, day3.get(0).amount());
        assertTrue(day3.stream().anyMatch(r -> r.type() == Reward.Type.ITEM));

        List<Reward> day99 = infinite.rewardsFor(99);
        assertEquals(200.0, day99.get(0).amount());
    }

    @Test
    void calendarFallsBackToNearestPreviousDay() {
        RewardGroup group = RewardGroup.calendar("default", "", 0,
                Map.of(1, List.of(Reward.parse("money:100")),
                        7, List.of(Reward.parse("money:700"))),
                List.of());

        assertEquals(700.0, group.rewardsFor(LocalDate.of(2025, 1, 10), 1, 1).get(0).amount());
        assertEquals(100.0, group.rewardsFor(LocalDate.of(2025, 1, 2), 1, 1).get(0).amount());
    }

    @Test
    void calendarUsesDefaultWhenConfigured() {
        RewardGroup group = RewardGroup.calendar("default", "", 0,
                Map.of(1, List.of(Reward.parse("money:100"))),
                List.of(Reward.parse("money:42")));
        assertEquals(42.0, group.rewardsFor(LocalDate.of(2025, 1, 31), 1, 1).get(0).amount());
    }
}
