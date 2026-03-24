package com.bytedance.tools.codelocator.utils;

import org.junit.Test;

import java.util.Arrays;
import java.util.List;

import static org.junit.Assert.assertEquals;

public class ActivityUtilsOrderTests {

    @Test
    public void prioritizeCurrentPreservesRelativeOrderOfOtherActivities() {
        List<String> ordered = ActivityUtils.prioritizeCurrentForStack(
                Arrays.asList("SecondActivity", "TopActivity", "ThirdActivity", "FourthActivity"),
                new ActivityUtils.RecordMatcher<String>() {
                    @Override
                    public boolean matches(String item) {
                        return "TopActivity".equals(item);
                    }
                }
        );

        assertEquals(
                Arrays.asList("TopActivity", "SecondActivity", "ThirdActivity", "FourthActivity"),
                ordered
        );
    }

    @Test
    public void prioritizeCurrentLeavesOrderUntouchedWhenCurrentAlreadyFirst() {
        List<String> ordered = ActivityUtils.prioritizeCurrentForStack(
                Arrays.asList("TopActivity", "SecondActivity", "ThirdActivity"),
                new ActivityUtils.RecordMatcher<String>() {
                    @Override
                    public boolean matches(String item) {
                        return "TopActivity".equals(item);
                    }
                }
        );

        assertEquals(
                Arrays.asList("TopActivity", "SecondActivity", "ThirdActivity"),
                ordered
        );
    }
}
