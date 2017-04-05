/* Copyright 2016 Urban Airship and Contributors */

package com.urbanairship.automation;

import android.os.Looper;
import android.support.annotation.Nullable;
import android.widget.Toast;

import com.urbanairship.BaseTestCase;
import com.urbanairship.PendingResult;
import com.urbanairship.TestActivityMonitor;
import com.urbanairship.TestApplication;
import com.urbanairship.UAirship;
import com.urbanairship.actions.ToastAction;
import com.urbanairship.analytics.CustomEvent;
import com.urbanairship.json.JsonValue;
import com.urbanairship.location.CircularRegion;
import com.urbanairship.location.ProximityRegion;
import com.urbanairship.location.RegionEvent;

import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.robolectric.Shadows;
import org.robolectric.shadows.ShadowLooper;
import org.robolectric.shadows.ShadowToast;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

import static junit.framework.Assert.assertEquals;
import static org.mockito.Matchers.anyInt;
import static org.mockito.Matchers.anyMap;
import static org.mockito.Matchers.anySet;
import static org.mockito.Matchers.anyString;
import static org.mockito.Mockito.atLeastOnce;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

public class AutomationTest extends BaseTestCase {

    private AutomationDataManager automationDataManager;
    private Automation automation;
    private TestActivityMonitor activityMonitor;

    private Trigger customEventTrigger;
    private ActionScheduleInfo customEventActionSchedule;
    private Map<String, List<String>> onEventAddedUpdatesMap;
    private Map<String, List<String>> handleTriggeredSchedulesUpdatesMap;


    @Before
    public void setUp() {
        activityMonitor = new TestActivityMonitor();
        activityMonitor.register();

        automationDataManager = mock(AutomationDataManager.class);
        automation = new Automation(UAirship.shared().getAnalytics(), automationDataManager, TestApplication.getApplication().preferenceDataStore, activityMonitor);
        automation.init();

        customEventTrigger = Triggers.newCustomEventTriggerBuilder()
                                  .setCountGoal(2)
                                  .setEventName("name")
                                  .build();

        customEventActionSchedule = ActionScheduleInfo.newBuilder()
                                                      .addTrigger(customEventTrigger)
                                                      .addAction("test_action", JsonValue.wrap("action_value"))
                                                      .setGroup("group")
                                                      .setLimit(4)
                                                      .setStart(System.currentTimeMillis())
                                                      .setEnd(System.currentTimeMillis() + 10000)
                                                      .build();

        onEventAddedUpdatesMap = new HashMap<>();
        handleTriggeredSchedulesUpdatesMap = new HashMap<>();

        onEventAddedUpdatesMap.put(String.format(AutomationDataManager.SCHEDULES_EXECUTION_DATE_UPDATE, "-1"), Collections.EMPTY_LIST);
        onEventAddedUpdatesMap.put(String.format(AutomationDataManager.SCHEDULES_EXECUTION_STATE_UPDATE, "0"), Collections.EMPTY_LIST);
        onEventAddedUpdatesMap.put(String.format(AutomationDataManager.TRIGGERS_TO_INCREMENT_QUERY, 1.0, "1"), Collections.EMPTY_LIST);
        onEventAddedUpdatesMap.put(String.format(AutomationDataManager.TRIGGERS_TO_INCREMENT_QUERY, 1.0, "0"), Collections.EMPTY_LIST);
        onEventAddedUpdatesMap.put(AutomationDataManager.TRIGGERS_TO_RESET_QUERY, Collections.EMPTY_LIST);

        handleTriggeredSchedulesUpdatesMap.put(AutomationDataManager.SCHEDULES_TO_DELETE_QUERY, Collections.EMPTY_LIST);
        handleTriggeredSchedulesUpdatesMap.put(AutomationDataManager.SCHEDULES_TO_INCREMENT_QUERY, Collections.EMPTY_LIST);
        handleTriggeredSchedulesUpdatesMap.put(AutomationDataManager.CANCELLATION_TRIGGERS_TO_RESET, Collections.EMPTY_LIST);
        handleTriggeredSchedulesUpdatesMap.put(String.format(AutomationDataManager.SCHEDULES_EXECUTION_DATE_UPDATE, "-1"), Collections.EMPTY_LIST);
        handleTriggeredSchedulesUpdatesMap.put(String.format(AutomationDataManager.SCHEDULES_EXECUTION_STATE_UPDATE, "0"), Collections.EMPTY_LIST);
        handleTriggeredSchedulesUpdatesMap.put(String.format(AutomationDataManager.SCHEDULES_EXECUTION_STATE_UPDATE, "1"), Collections.EMPTY_LIST);
    }

    @After
    public void takeDown() {
        onEventAddedUpdatesMap.clear();
        handleTriggeredSchedulesUpdatesMap.clear();
        automation.tearDown();
        activityMonitor.unregister();
    }

    @Test
    public void testCustomEventMatch() throws Exception {
        when(automationDataManager.insertSchedules(Collections.singletonList(customEventActionSchedule))).thenReturn(Collections.singletonList(new ActionSchedule("automation id", customEventActionSchedule, 0, false, -1L)));
        String id  = automation.schedule(customEventActionSchedule).getId();

        assertEquals("automation id", id);

        TriggerEntry triggerEntry = new TriggerEntry(customEventTrigger.getType(), customEventTrigger.getGoal(), customEventTrigger.getPredicate(), "1", "automation id", null, 0.0);

        List<TriggerEntry> triggerEntries = new ArrayList<>();
        for (int i = 0; i < 5; i++) {
            triggerEntries.add(new TriggerEntry(Trigger.CUSTOM_EVENT_COUNT, 5, Triggers.newCustomEventTriggerBuilder().setCountGoal(5).setEventName("other name").build().getPredicate(), String.valueOf(i), "id " + i, null, 0.0));
        }
        triggerEntries.add(triggerEntry);

        when(automationDataManager.getActiveTriggers(Trigger.CUSTOM_EVENT_COUNT)).thenReturn(triggerEntries);
        when(automationDataManager.getSchedules(anySet())).thenReturn(Collections.singletonList(new ActionSchedule("automation id", customEventActionSchedule, 0, false, -1L)));

        new CustomEvent.Builder("name")
                .create()
                .track();

        runLooperTasks();

        verify(automationDataManager).getActiveTriggers(anyInt());
        verify(automationDataManager, never()).getSchedules(anySet());

        onEventAddedUpdatesMap.put(String.format(AutomationDataManager.TRIGGERS_TO_INCREMENT_QUERY, 1.0, "0"), Collections.singletonList("1"));

        verify(automationDataManager).updateLists(onEventAddedUpdatesMap);
    }

    @Test
    public void testCustomEventValueMatch() throws Exception {
        // This will test that if the custom event value gets reduced to an integer in the action parsing process, a proper comparison will still be made in the value matching.
        String json = "{\"actions\":" +
                "{\"test_action\":\"action_value\"}," +
                "\"limit\": 5," +
                "\"group\": \"group\"," +
                "\"triggers\": [" +
                    "{" +
                        "\"type\": \"custom_event_value\"," +
                        "\"goal\": 4.0," +
                        "\"predicate\": {" +
                            "\"and\" : [" +
                                "{\"key\": \"event_name\",\"value\": {\"equals\": \"name\"}}," +
                                "{\"key\": \"event_value\",\"value\": {\"equals\": 5}}" +
                            "]" +
                        "}" +
                    "}" +
                "]}";

        ActionScheduleInfo actionScheduleInfo = ActionScheduleInfo.parseJson(JsonValue.parseString(json));
        Trigger trigger = actionScheduleInfo.getTriggers().get(0);
        TriggerEntry triggerEntry = new TriggerEntry(trigger.getType(), trigger.getGoal(), trigger.getPredicate(), "1", "automation id", null, 0.0);

        when(automationDataManager.insertSchedules(Collections.singletonList(actionScheduleInfo))).thenReturn(Collections.singletonList(new ActionSchedule("automation id", actionScheduleInfo, 0, false, -1L)));
        automation.schedule(actionScheduleInfo);

        when(automationDataManager.getActiveTriggers(Trigger.CUSTOM_EVENT_VALUE)).thenReturn(Collections.singletonList(triggerEntry));
        when(automationDataManager.getSchedules(anySet())).thenReturn(Collections.singletonList(new ActionSchedule("automation id", actionScheduleInfo, 0, false, -1L)));
        when(automationDataManager.getSchedule(anyString())).thenReturn(new ActionSchedule("automation id", actionScheduleInfo, 0, false, -1L));

        new CustomEvent.Builder("name")
                .setEventValue(5.0)
                .create()
                .track();

        runLooperTasks();

        verify(automationDataManager, atLeastOnce()).getActiveTriggers(anyInt());

        handleTriggeredSchedulesUpdatesMap.put(AutomationDataManager.SCHEDULES_TO_INCREMENT_QUERY, Collections.singletonList("automation id"));
        handleTriggeredSchedulesUpdatesMap.put(String.format(AutomationDataManager.SCHEDULES_EXECUTION_DATE_UPDATE, "-1"), Collections.singletonList("automation id"));
        handleTriggeredSchedulesUpdatesMap.put(String.format(AutomationDataManager.SCHEDULES_EXECUTION_STATE_UPDATE, "0"), Collections.singletonList("automation id"));
        verify(automationDataManager).updateLists(handleTriggeredSchedulesUpdatesMap);
    }

    @Test
    public void testRegionEventValueMatch() throws Exception {
        String json = "{\"actions\":" +
                "{\"test_action\":\"action_value\"}," +
                "\"limit\": 5," +
                "\"group\": \"group\"," +
                "\"triggers\": [" +
                    "{" +
                        "\"type\": \"region_enter\"," +
                        "\"goal\": 4.0," +
                        "\"predicate\": {" +
                            "\"and\" : [" +
                                "{\"key\": \"region_id\",\"value\": {\"equals\": \"region_id\"}}," +
                                "{\"key\": \"latitude\",\"scope\":[\"proximity\"],\"value\": {\"equals\": 5.0}}," +
                                "{\"key\": \"source\",\"value\": {\"equals\": \"test_source\"}}" +
                            "]" +
                        "}" +
                    "}" +
                "]}";

        ActionScheduleInfo actionScheduleInfo = ActionScheduleInfo.parseJson(JsonValue.parseString(json));
        Trigger trigger = actionScheduleInfo.getTriggers().get(0);
        TriggerEntry triggerEntry = new TriggerEntry(trigger.getType(), trigger.getGoal(), trigger.getPredicate(), "1", "automation id", null, 0.0);

        when(automationDataManager.insertSchedules(Collections.singletonList(actionScheduleInfo))).thenReturn(Collections.singletonList(new ActionSchedule("automation id", actionScheduleInfo, 0, false, -1L)));
        automation.schedule(actionScheduleInfo);

        when(automationDataManager.getActiveTriggers(Trigger.REGION_ENTER)).thenReturn(Collections.singletonList(triggerEntry));
        when(automationDataManager.getSchedules(anySet())).thenReturn(Collections.singletonList(new ActionSchedule("automation id", actionScheduleInfo, 0, false, -1L)));

        RegionEvent event = new RegionEvent("region_id", "test_source", RegionEvent.BOUNDARY_EVENT_ENTER);
        ProximityRegion proximityRegion = new ProximityRegion("id", 2, 3);
        proximityRegion.setCoordinates(5.0, 6.0);
        CircularRegion circularRegion = new CircularRegion(1.0, 2.0, 3.0);
        event.setProximityRegion(proximityRegion);
        event.setCircularRegion(circularRegion);

        UAirship.shared().getAnalytics().addEvent(event);

        runLooperTasks();

        verify(automationDataManager, atLeastOnce()).getActiveTriggers(anyInt());
        onEventAddedUpdatesMap.put(String.format(AutomationDataManager.TRIGGERS_TO_INCREMENT_QUERY, 1.0, "0"), Collections.singletonList("1"));
        verify(automationDataManager).updateLists(onEventAddedUpdatesMap);
    }

    @Test
    public void testCustomEventNoMatch() throws Exception {
        when(automationDataManager.insertSchedules(Collections.singletonList(customEventActionSchedule))).thenReturn(Collections.singletonList(new ActionSchedule("automation id", customEventActionSchedule, 0, false, -1L)));
        String id  = automation.schedule(customEventActionSchedule).getId();

        assertEquals("automation id", id);

        TriggerEntry triggerEntry = new TriggerEntry(customEventTrigger.getType(), customEventTrigger.getGoal(), customEventTrigger.getPredicate(), "1", "automation id", null, 0.0);
        when(automationDataManager.getActiveTriggers(Trigger.CUSTOM_EVENT_COUNT)).thenReturn(Collections.singletonList(triggerEntry));
        when(automationDataManager.getSchedules(anySet())).thenReturn(Collections.singletonList(new ActionSchedule("automation id", customEventActionSchedule, 0, false, -1L)));

        new CustomEvent.Builder("other name")
                .create()
                .track();

        runLooperTasks();

        verify(automationDataManager).getActiveTriggers(anyInt());
        verify(automationDataManager, never()).getSchedules(anySet());

        onEventAddedUpdatesMap.put(String.format(AutomationDataManager.TRIGGERS_TO_INCREMENT_QUERY, 1.0, "0"), Collections.EMPTY_LIST);
        verify(automationDataManager).updateLists(onEventAddedUpdatesMap);
    }

    @Test
    public void testCustomEventScheduleFulfillment() throws Exception {
        when(automationDataManager.insertSchedules(Collections.singletonList(customEventActionSchedule))).thenReturn(Collections.singletonList(new ActionSchedule("automation id", customEventActionSchedule, 0, false, -1L)));
        String id  = automation.schedule(customEventActionSchedule).getId();

        assertEquals("automation id", id);

        TriggerEntry triggerEntry = new TriggerEntry(customEventTrigger.getType(), customEventTrigger.getGoal(), customEventTrigger.getPredicate(), "1", "customEventActionSchedule id", null, 1.0);
        when(automationDataManager.getActiveTriggers(Trigger.CUSTOM_EVENT_COUNT)).thenReturn(Collections.singletonList(triggerEntry));
        when(automationDataManager.getSchedules(anySet())).thenReturn(Collections.singletonList(new ActionSchedule("automation id", customEventActionSchedule, 0, false, -1L)));
        when(automationDataManager.getSchedule(anyString())).thenReturn(new ActionSchedule("automation id", customEventActionSchedule, 0, false, -1L));

        new CustomEvent.Builder("name")
                .create()
                .track();

        runLooperTasks();

        verify(automationDataManager).getActiveTriggers(anyInt());
        verify(automationDataManager).getSchedules(anySet());

        handleTriggeredSchedulesUpdatesMap.put(AutomationDataManager.SCHEDULES_TO_INCREMENT_QUERY, Collections.singletonList("automation id"));
        handleTriggeredSchedulesUpdatesMap.put(String.format(AutomationDataManager.SCHEDULES_EXECUTION_DATE_UPDATE, "-1"), Collections.singletonList("automation id"));
        handleTriggeredSchedulesUpdatesMap.put(String.format(AutomationDataManager.SCHEDULES_EXECUTION_STATE_UPDATE, "0"), Collections.singletonList("automation id"));
        verify(automationDataManager).updateLists(handleTriggeredSchedulesUpdatesMap);
    }

    @Test
    public void testCustomEventScheduleLimitReached() throws Exception {
        when(automationDataManager.insertSchedules(Collections.singletonList(customEventActionSchedule))).thenReturn(Collections.singletonList(new ActionSchedule("automation id", customEventActionSchedule, 0, false, -1L)));
        String id  = automation.schedule(customEventActionSchedule).getId();

        assertEquals("automation id", id);

        TriggerEntry triggerEntry = new TriggerEntry(customEventTrigger.getType(), customEventTrigger.getGoal(), customEventTrigger.getPredicate(), "1", "automation id", null, 1.0);
        when(automationDataManager.getActiveTriggers(Trigger.CUSTOM_EVENT_COUNT)).thenReturn(Collections.singletonList(triggerEntry));
        when(automationDataManager.getSchedules(anySet())).thenReturn(Collections.singletonList(new ActionSchedule("automation id", customEventActionSchedule, 3, false, -1L)));
        when(automationDataManager.getSchedule(anyString())).thenReturn(new ActionSchedule("automation id", customEventActionSchedule, 0, false, -1L));

        new CustomEvent.Builder("name")
                .create()
                .track();

        runLooperTasks();

        verify(automationDataManager).getActiveTriggers(anyInt());
        verify(automationDataManager).getSchedules(anySet());

        handleTriggeredSchedulesUpdatesMap.put(AutomationDataManager.SCHEDULES_TO_DELETE_QUERY, Collections.singletonList("automation id"));
        verify(automationDataManager).updateLists(handleTriggeredSchedulesUpdatesMap);
    }

    @Test
    public void testCustomEventNoTriggers() throws Exception {
        when(automationDataManager.insertSchedules(Collections.singletonList(customEventActionSchedule))).thenReturn(Collections.singletonList(new ActionSchedule("automation id", customEventActionSchedule, 0, false, -1L)));
        String id  = automation.schedule(customEventActionSchedule).getId();

        assertEquals("automation id", id);

        when(automationDataManager.getActiveTriggers(Trigger.CUSTOM_EVENT_COUNT)).thenReturn(Collections.EMPTY_LIST);

        new CustomEvent.Builder("name")
                .create()
                .track();

        runLooperTasks();

        verify(automationDataManager).getActiveTriggers(anyInt());
        verify(automationDataManager, never()).getSchedules(anySet());
        verify(automationDataManager, never()).updateLists(anyMap());
    }

    @Test
    public void testEnterRegionEvent() throws Exception {
        Trigger enter = Triggers.newEnterRegionTriggerBuilder()
                .setRegionId("region_id")
                .setGoal(2)
                .build();

        ActionScheduleInfo schedule = ActionScheduleInfo.newBuilder()
                .setLimit(5)
                .setGroup("group")
                .addTrigger(enter)
                .addAction("test_action", JsonValue.wrap("action_value"))
                .build();

        when(automationDataManager.insertSchedules(Collections.singletonList(schedule))).thenReturn(Collections.singletonList(new ActionSchedule("automation id", schedule, 0, false, -1L)));
        String id  = automation.schedule(schedule).getId();
        assertEquals("automation id", id);

        TriggerEntry enterEntry = new TriggerEntry(enter.getType(), enter.getGoal(), enter.getPredicate(), "1", "automation id", null, 0.0);
        when(automationDataManager.getActiveTriggers(Trigger.REGION_ENTER)).thenReturn(Collections.singletonList(enterEntry));

        RegionEvent event = new RegionEvent("region_id", "source", RegionEvent.BOUNDARY_EVENT_ENTER);
        UAirship.shared().getAnalytics().addEvent(event);

        runLooperTasks();

        verify(automationDataManager).getActiveTriggers(Trigger.REGION_ENTER);
        verify(automationDataManager, never()).getActiveTriggers(Trigger.REGION_EXIT);

        onEventAddedUpdatesMap.put(String.format(AutomationDataManager.TRIGGERS_TO_INCREMENT_QUERY, 1.0, "0"), Collections.singletonList("1"));
        verify(automationDataManager).updateLists(onEventAddedUpdatesMap);
    }

    @Test
    public void testExitRegionEvent() throws Exception {
        Trigger trigger = Triggers.newExitRegionTriggerBuilder()
                               .setRegionId("region_id")
                               .setGoal(2)
                               .build();

        ActionScheduleInfo schedule = ActionScheduleInfo.newBuilder()
                                                        .setLimit(5)
                                                        .setGroup("group")
                                                        .addTrigger(trigger)
                                                        .addAction("test_action", JsonValue.wrap("action_value"))
                                                        .build();

        when(automationDataManager.insertSchedules(Collections.singletonList(schedule))).thenReturn(Collections.singletonList(new ActionSchedule("automation id", schedule, 0, false, -1L)));
        String id  = automation.schedule(schedule).getId();
        assertEquals("automation id", id);

        TriggerEntry triggerEntry = new TriggerEntry(trigger.getType(), trigger.getGoal(), trigger.getPredicate(), "1", "automation id", null, 0.0);
        when(automationDataManager.getActiveTriggers(Trigger.REGION_EXIT)).thenReturn(Collections.singletonList(triggerEntry));

        RegionEvent event = new RegionEvent("region_id", "source", RegionEvent.BOUNDARY_EVENT_EXIT);
        ProximityRegion proximityRegion = new ProximityRegion("id", 2, 3);
        CircularRegion circularRegion = new CircularRegion(1.0, 2.0, 3.0);

        event.setProximityRegion(proximityRegion);
        event.setCircularRegion(circularRegion);
        UAirship.shared().getAnalytics().addEvent(event);

        runLooperTasks();

        verify(automationDataManager, never()).getActiveTriggers(Trigger.REGION_ENTER);
        verify(automationDataManager).getActiveTriggers(Trigger.REGION_EXIT);

        onEventAddedUpdatesMap.put(String.format(AutomationDataManager.TRIGGERS_TO_INCREMENT_QUERY, 1.0, "0"), Collections.singletonList("1"));
        verify(automationDataManager).updateLists(onEventAddedUpdatesMap);
    }

    @Test
    public void testAppForegroundEvent() throws Exception {
        Trigger trigger = Triggers.newForegroundTriggerBuilder()
                .setGoal(3)
                .build();

        ActionScheduleInfo schedule = ActionScheduleInfo.newBuilder()
                .addAction("test_action", JsonValue.wrap("action_value"))
                .addTrigger(trigger)
                .setGroup("group")
                .setLimit(5)
                .build();


        when(automationDataManager.insertSchedules(Collections.singletonList(schedule))).thenReturn(Collections.singletonList(new ActionSchedule("automation id", schedule, 0, false, -1L)));
        String id  = automation.schedule(schedule).getId();

        assertEquals("automation id", id);

        TriggerEntry triggerEntry = new TriggerEntry(trigger.getType(), trigger.getGoal(), trigger.getPredicate(), "1", "automation id", null, 0.0);
        when(automationDataManager.getActiveTriggers(Trigger.LIFE_CYCLE_FOREGROUND)).thenReturn(Collections.singletonList(triggerEntry));
        when(automationDataManager.getSchedules(anySet())).thenReturn(Collections.singletonList(new ActionSchedule("automation id", schedule, 0, false, -1L)));

        activityMonitor.startActivity();

        runLooperTasks();

        verify(automationDataManager).getActiveTriggers(anyInt());
        verify(automationDataManager, never()).getSchedules(anySet());

        onEventAddedUpdatesMap.put(String.format(AutomationDataManager.TRIGGERS_TO_INCREMENT_QUERY, 1.0, "0"), Collections.singletonList("1"));
        verify(automationDataManager).updateLists(onEventAddedUpdatesMap);
    }

    @Test
    public void testAppBackgroundEvent() throws Exception {
        Trigger trigger = Triggers.newBackgroundTriggerBuilder()
                                  .setGoal(3)
                                  .build();

        ActionScheduleInfo schedule = ActionScheduleInfo.newBuilder()
                                                        .addAction("test_action", JsonValue.wrap("action_value"))
                                                        .addTrigger(trigger)
                                                        .setGroup("group")
                                                        .setLimit(5)
                                                        .build();


        when(automationDataManager.insertSchedules(Collections.singletonList(schedule))).thenReturn(Collections.singletonList(new ActionSchedule("automation id", schedule, 0, false, -1L)));
        String id  = automation.schedule(schedule).getId();

        assertEquals("automation id", id);

        TriggerEntry triggerEntry = new TriggerEntry(trigger.getType(), trigger.getGoal(), trigger.getPredicate(), "1", "automation id", null, 0.0);
        when(automationDataManager.getActiveTriggers(Trigger.LIFE_CYCLE_BACKGROUND)).thenReturn(Collections.singletonList(triggerEntry));
        when(automationDataManager.getSchedules(anySet())).thenReturn(Collections.singletonList(new ActionSchedule("automation id", schedule, 0, false, -1L)));

        activityMonitor.startActivity();
        activityMonitor.stopActivity();

        runLooperTasks();

        verify(automationDataManager, atLeastOnce()).getActiveTriggers(anyInt());
        verify(automationDataManager, never()).getSchedules(anySet());

        onEventAddedUpdatesMap.put(String.format(AutomationDataManager.TRIGGERS_TO_INCREMENT_QUERY, 1.0, "0"), Collections.singletonList("1"));
        verify(automationDataManager).updateLists(onEventAddedUpdatesMap);
    }

    @Test
    public void testScreenEvent() throws Exception {
        Trigger trigger = Triggers.newScreenTriggerBuilder()
                                  .setGoal(3)
                                  .setScreenName("screen")
                                  .build();

        ActionScheduleInfo schedule = ActionScheduleInfo.newBuilder()
                                                        .addAction("test_action", JsonValue.wrap("action_value"))
                                                        .addTrigger(trigger)
                                                        .setGroup("group")
                                                        .setLimit(5)
                                                        .build();


        when(automationDataManager.insertSchedules(Collections.singletonList(schedule))).thenReturn(Collections.singletonList(new ActionSchedule("automation id", schedule, 0, false, -1L)));
        String id  = automation.schedule(schedule).getId();

        assertEquals("automation id", id);

        TriggerEntry triggerEntry = new TriggerEntry(trigger.getType(), trigger.getGoal(), trigger.getPredicate(), "1", "automation id", null, 0.0);
        when(automationDataManager.getActiveTriggers(Trigger.SCREEN_VIEW)).thenReturn(Collections.singletonList(triggerEntry));
        when(automationDataManager.getSchedules(anySet())).thenReturn(Collections.singletonList(new ActionSchedule("automation id", schedule, 0, false, -1L)));

        UAirship.shared().getAnalytics().trackScreen("screen");

        runLooperTasks();

        verify(automationDataManager).getActiveTriggers(anyInt());
        verify(automationDataManager, never()).getSchedules(anySet());

        onEventAddedUpdatesMap.put(String.format(AutomationDataManager.TRIGGERS_TO_INCREMENT_QUERY, 1.0, "0"), Collections.singletonList("1"));
        verify(automationDataManager).updateLists(onEventAddedUpdatesMap);
    }

    @Test
    public void testExpired() throws Exception {
        Trigger trigger = Triggers.newScreenTriggerBuilder()
                                  .setGoal(3)
                                  .setScreenName("screen")
                                  .build();

        ActionScheduleInfo schedule = ActionScheduleInfo.newBuilder()
                                                        .addAction("test_action", JsonValue.wrap("action_value"))
                                                        .addTrigger(trigger)
                                                        .setGroup("group")
                                                        .setLimit(5)
                                                        .setEnd(System.currentTimeMillis() - 100)
                                                        .build();

        when(automationDataManager.insertSchedules(Collections.singletonList(schedule))).thenReturn(Collections.singletonList(new ActionSchedule("automation id", schedule, 0, false, -1L)));
        String id  = automation.schedule(schedule).getId();

        assertEquals("automation id", id);

        TriggerEntry triggerEntry = new TriggerEntry(trigger.getType(), trigger.getGoal(), trigger.getPredicate(), "1", "automation id", null, 2.0);
        when(automationDataManager.getActiveTriggers(Trigger.SCREEN_VIEW)).thenReturn(Collections.singletonList(triggerEntry));
        when(automationDataManager.getSchedules(anySet())).thenReturn(Collections.singletonList(new ActionSchedule("automation id", schedule, 0, false, -1L)));

        UAirship.shared().getAnalytics().trackScreen("screen");

        runLooperTasks();

        verify(automationDataManager).getActiveTriggers(anyInt());
        verify(automationDataManager).getSchedules(anySet());

        handleTriggeredSchedulesUpdatesMap.put(AutomationDataManager.SCHEDULES_TO_DELETE_QUERY, Collections.singletonList("automation id"));
        verify(automationDataManager).updateLists(handleTriggeredSchedulesUpdatesMap);
    }

    @Test
    public void testScheduleAsync() throws Exception {
        when(automationDataManager.insertSchedules(Collections.singletonList(customEventActionSchedule))).thenReturn(Collections.singletonList(new ActionSchedule("automation id", customEventActionSchedule, 0, false, -1L)));

        final CountDownLatch latch = new CountDownLatch(1);
        automation.scheduleAsync(customEventActionSchedule, new PendingResult.ResultCallback<ActionSchedule>() {
            @Override
            public void onResult(@Nullable ActionSchedule result) {
                latch.countDown();
            }
        });

        while (!latch.await(1, TimeUnit.MILLISECONDS)) {
            runLooperTasks();
        }

        verify(automationDataManager).insertSchedules(Collections.singletonList(customEventActionSchedule));
    }

    @Test
    public void testInactivityWithoutSchedules() throws Exception {
        when(automationDataManager.insertSchedules(Collections.singletonList(customEventActionSchedule))).thenReturn(Collections.singletonList(new ActionSchedule("automation id", customEventActionSchedule, 0, false, -1L)));

        new CustomEvent.Builder("name")
                .create()
                .track();

        runLooperTasks();

        verify(automationDataManager, never()).getActiveTriggers(anyInt());
        automation.schedule(customEventActionSchedule);

        new CustomEvent.Builder("name")
                .create()
                .track();

        runLooperTasks();

        verify(automationDataManager).getActiveTriggers(anyInt());
    }

    @Test
    public void testSecondsDelay() throws Exception {
        Trigger trigger = Triggers.newForegroundTriggerBuilder()
                                  .setGoal(1)
                                  .build();

        Map<String, Object> toastMap = new HashMap<>();
        toastMap.put("length", Toast.LENGTH_LONG);
        toastMap.put("text", "toast");

        ScheduleDelay delay = ScheduleDelay.newBuilder()
                .setSeconds(1)
                .build();

        ActionScheduleInfo schedule = ActionScheduleInfo.newBuilder()
                                                        .addAction(ToastAction.DEFAULT_REGISTRY_NAME, JsonValue.wrap(toastMap))
                                                        .addTrigger(trigger)
                                                        .setDelay(delay)
                                                        .setGroup("group")
                                                        .setLimit(5)
                                                        .build();


        when(automationDataManager.insertSchedules(Collections.singletonList(schedule))).thenReturn(Collections.singletonList(new ActionSchedule("automation id", schedule, 0, false, -1L)));
        String id  = automation.schedule(schedule).getId();

        assertEquals("automation id", id);

        TriggerEntry triggerEntry = new TriggerEntry(trigger.getType(), trigger.getGoal(), trigger.getPredicate(), "1", "automation id", null, 0.0);
        when(automationDataManager.getActiveTriggers(Trigger.LIFE_CYCLE_FOREGROUND)).thenReturn(Collections.singletonList(triggerEntry));
        when(automationDataManager.getSchedules(anySet())).thenReturn(Collections.singletonList(new ActionSchedule("automation id", schedule, 0, false, -1L)));
        when(automationDataManager.getSchedule(anyString())).thenReturn(new ActionSchedule("automation id", schedule, 0, true, 1234L));

        activityMonitor.startActivity();
        runLooperTasks();

        verify(automationDataManager).getActiveTriggers(anyInt());
        verify(automationDataManager).getSchedules(anySet());
        verify(automationDataManager, times(2)).updateLists(anyMap());

        // Verify that the toast doesn't happen until after schedule
        assertEquals(null, ShadowToast.getTextOfLatestToast());
        advanceAutomationLooperScheduler(1000);
        assertEquals("toast", ShadowToast.getTextOfLatestToast());
    }

    @Test
    public void testSecondsDelayWithAppState() throws Exception {
        Trigger trigger = Triggers.newForegroundTriggerBuilder()
                                  .setGoal(1)
                                  .build();

        Map<String, Object> toastMap = new HashMap<>();
        toastMap.put("length", Toast.LENGTH_LONG);
        toastMap.put("text", "toast");

        ScheduleDelay delay = ScheduleDelay.newBuilder()
                                           .setSeconds(1)
                                           .setAppState(ScheduleDelay.APP_STATE_FOREGROUND)
                                           .build();

        ActionScheduleInfo schedule = ActionScheduleInfo.newBuilder()
                                                        .addAction(ToastAction.DEFAULT_REGISTRY_NAME, JsonValue.wrap(toastMap))
                                                        .addTrigger(trigger)
                                                        .setDelay(delay)
                                                        .setGroup("group")
                                                        .setLimit(5)
                                                        .build();


        when(automationDataManager.insertSchedules(Collections.singletonList(schedule))).thenReturn(Collections.singletonList(new ActionSchedule("automation id", schedule, 0, false, -1L)));
        String id  = automation.schedule(schedule).getId();

        assertEquals("automation id", id);

        TriggerEntry triggerEntry = new TriggerEntry(trigger.getType(), trigger.getGoal(), trigger.getPredicate(), "1", "automation id", null, 0.0);
        when(automationDataManager.getActiveTriggers(Trigger.LIFE_CYCLE_FOREGROUND)).thenReturn(Collections.singletonList(triggerEntry));
        when(automationDataManager.getSchedules(anySet())).thenReturn(Collections.singletonList(new ActionSchedule("automation id", schedule, 0, false, -1L)));
        when(automationDataManager.getDelayedSchedules()).thenReturn(Collections.<ActionSchedule>emptyList())
                .thenReturn(Collections.<ActionSchedule>emptyList())
                .thenReturn(Collections.singletonList(new ActionSchedule("automation id", schedule, 0, true, 1234L)));
        when(automationDataManager.getSchedule(anyString())).thenReturn(new ActionSchedule("automation id", schedule, 0, true, 1234L));

        activityMonitor.startActivity();
        runLooperTasks();

        verify(automationDataManager).getActiveTriggers(anyInt());
        verify(automationDataManager).getSchedules(anySet());
        verify(automationDataManager, times(2)).updateLists(anyMap());

        // Verify that the toast doesn't happen until the time passes + activity resumes.
        assertEquals(null, ShadowToast.getTextOfLatestToast());
        activityMonitor.stopActivity();
        advanceAutomationLooperScheduler(1000);
        assertEquals(null, ShadowToast.getTextOfLatestToast());
        activityMonitor.startActivity();
        runLooperTasks();
        assertEquals("toast", ShadowToast.getTextOfLatestToast());
    }

    @Test
    public void testSecondsDelayWithScreen() throws Exception {
        Trigger trigger = Triggers.newForegroundTriggerBuilder()
                                  .setGoal(1)
                                  .build();

        Map<String, Object> toastMap = new HashMap<>();
        toastMap.put("length", Toast.LENGTH_LONG);
        toastMap.put("text", "toast");

        ScheduleDelay delay = ScheduleDelay.newBuilder()
                                           .setSeconds(1)
                                           .setScreen("the-screen")
                                           .build();

        ActionScheduleInfo schedule = ActionScheduleInfo.newBuilder()
                                                        .addAction(ToastAction.DEFAULT_REGISTRY_NAME, JsonValue.wrap(toastMap))
                                                        .addTrigger(trigger)
                                                        .setDelay(delay)
                                                        .setGroup("group")
                                                        .setLimit(5)
                                                        .build();


        when(automationDataManager.insertSchedules(Collections.singletonList(schedule))).thenReturn(Collections.singletonList(new ActionSchedule("automation id", schedule, 0, false, -1L)));
        String id  = automation.schedule(schedule).getId();

        assertEquals("automation id", id);

        TriggerEntry triggerEntry = new TriggerEntry(trigger.getType(), trigger.getGoal(), trigger.getPredicate(), "1", "automation id", null, 0.0);
        when(automationDataManager.getActiveTriggers(Trigger.LIFE_CYCLE_FOREGROUND)).thenReturn(Collections.singletonList(triggerEntry));
        when(automationDataManager.getSchedules(anySet())).thenReturn(Collections.singletonList(new ActionSchedule("automation id", schedule, 0, false, -1L)));
        when(automationDataManager.getDelayedSchedules()).thenReturn(Collections.<ActionSchedule>emptyList())
                                                         .thenReturn(Collections.<ActionSchedule>emptyList())
                                                         .thenReturn(Collections.singletonList(new ActionSchedule("automation id", schedule, 0, true, 1234L)));
        when(automationDataManager.getSchedule(anyString())).thenReturn(new ActionSchedule("automation id", schedule, 0, true, 1234L));

        activityMonitor.startActivity();
        runLooperTasks();

        verify(automationDataManager).getActiveTriggers(anyInt());
        verify(automationDataManager).getSchedules(anySet());
        verify(automationDataManager, times(2)).updateLists(anyMap());

        // Verify that the toast doesn't happen until the time passes + activity resumes.
        assertEquals(null, ShadowToast.getTextOfLatestToast());
        advanceAutomationLooperScheduler(1000);
        assertEquals(null, ShadowToast.getTextOfLatestToast());
        UAirship.shared().getAnalytics().trackScreen("the-screen");
        runLooperTasks();
        assertEquals("toast", ShadowToast.getTextOfLatestToast());
    }

    @Test
    public void testSecondsDelayWithRegion() throws Exception {
        Trigger trigger = Triggers.newForegroundTriggerBuilder()
                                  .setGoal(1)
                                  .build();

        Map<String, Object> toastMap = new HashMap<>();
        toastMap.put("length", Toast.LENGTH_LONG);
        toastMap.put("text", "toast");

        ScheduleDelay delay = ScheduleDelay.newBuilder()
                                           .setSeconds(1)
                                           .setRegionId("enter_region")
                                           .build();

        ActionScheduleInfo schedule = ActionScheduleInfo.newBuilder()
                                                        .addAction(ToastAction.DEFAULT_REGISTRY_NAME, JsonValue.wrap(toastMap))
                                                        .addTrigger(trigger)
                                                        .setDelay(delay)
                                                        .setGroup("group")
                                                        .setLimit(5)
                                                        .build();


        when(automationDataManager.insertSchedules(Collections.singletonList(schedule))).thenReturn(Collections.singletonList(new ActionSchedule("automation id", schedule, 0, false, -1L)));
        String id  = automation.schedule(schedule).getId();

        assertEquals("automation id", id);

        TriggerEntry triggerEntry = new TriggerEntry(trigger.getType(), trigger.getGoal(), trigger.getPredicate(), "1", "automation id", null, 0.0);
        when(automationDataManager.getActiveTriggers(Trigger.LIFE_CYCLE_FOREGROUND)).thenReturn(Collections.singletonList(triggerEntry));
        when(automationDataManager.getSchedules(anySet())).thenReturn(Collections.singletonList(new ActionSchedule("automation id", schedule, 0, false, -1L)));
        when(automationDataManager.getDelayedSchedules()).thenReturn(Collections.<ActionSchedule>emptyList())
                                                         .thenReturn(Collections.<ActionSchedule>emptyList())
                                                         .thenReturn(Collections.singletonList(new ActionSchedule("automation id", schedule, 0, true, 1234L)));
        when(automationDataManager.getSchedule(anyString())).thenReturn(new ActionSchedule("automation id", schedule, 0, true, 1234L));

        activityMonitor.startActivity();
        runLooperTasks();

        verify(automationDataManager).getActiveTriggers(anyInt());
        verify(automationDataManager).getSchedules(anySet());
        verify(automationDataManager, times(2)).updateLists(anyMap());

        // Verify that the toast doesn't happen until the time passes + region is entered.
        assertEquals(null, ShadowToast.getTextOfLatestToast());
        advanceAutomationLooperScheduler(1000);
        assertEquals(null, ShadowToast.getTextOfLatestToast());

        RegionEvent event = new RegionEvent("enter_region", "test_source", RegionEvent.BOUNDARY_EVENT_ENTER);
        UAirship.shared().getAnalytics().addEvent(event);
        runLooperTasks();
        assertEquals("toast", ShadowToast.getTextOfLatestToast());
    }

    @Test
    public void testRescheduleDelayRemainingExecution() throws Exception {
        Trigger trigger = Triggers.newForegroundTriggerBuilder()
                                  .setGoal(1)
                                  .build();

        Map<String, Object> toastMap = new HashMap<>();
        toastMap.put("length", Toast.LENGTH_LONG);
        toastMap.put("text", "toast");

        ScheduleDelay delay = ScheduleDelay.newBuilder()
                                           .setSeconds(1)
                                           .setAppState(ScheduleDelay.APP_STATE_FOREGROUND)
                                           .build();

        ActionScheduleInfo schedule = ActionScheduleInfo.newBuilder()
                                                        .addAction(ToastAction.DEFAULT_REGISTRY_NAME, JsonValue.wrap(toastMap))
                                                        .addTrigger(trigger)
                                                        .setDelay(delay)
                                                        .setGroup("group")
                                                        .setLimit(5)
                                                        .build();


        when(automationDataManager.insertSchedules(Collections.singletonList(schedule))).thenReturn(Collections.singletonList(new ActionSchedule("automation id", schedule, 0, false, -1L)));
        String id  = automation.schedule(schedule).getId();

        assertEquals("automation id", id);

        TriggerEntry triggerEntry = new TriggerEntry(trigger.getType(), trigger.getGoal(), trigger.getPredicate(), "1", "automation id", null, 0.0);
        when(automationDataManager.getActiveTriggers(Trigger.LIFE_CYCLE_FOREGROUND)).thenReturn(Collections.singletonList(triggerEntry));
        when(automationDataManager.getSchedules(anySet())).thenReturn(Collections.singletonList(new ActionSchedule("automation id", schedule, 0, false, -1L)));
        when(automationDataManager.getSchedule(anyString())).thenReturn(new ActionSchedule("automation id", schedule, 0, true, 1234L));

        activityMonitor.startActivity();
        runLooperTasks();

        verify(automationDataManager).getActiveTriggers(anyInt());
        verify(automationDataManager).getSchedules(anySet());
        verify(automationDataManager, times(2)).updateLists(anyMap());

        // Verify that the toast doesn't happen until after schedule
        assertEquals(null, ShadowToast.getTextOfLatestToast());
        advanceAutomationLooperScheduler(700);
        assertEquals(null, ShadowToast.getTextOfLatestToast());
        activityMonitor.stopActivity();
        activityMonitor.startActivity();
        assertEquals(null, ShadowToast.getTextOfLatestToast());
        advanceAutomationLooperScheduler(300);
        assertEquals("toast", ShadowToast.getTextOfLatestToast());
    }


    /**
     * Helper method to run all the looper tasks.
     */
    private void runLooperTasks() {
        ShadowLooper mainLooper = Shadows.shadowOf(Looper.getMainLooper());
        ShadowLooper automationLooper = Shadows.shadowOf(automation.backgroundThread.getLooper());

        while (mainLooper.getScheduler().areAnyRunnable() || automationLooper.getScheduler().areAnyRunnable()) {
            mainLooper.runToEndOfTasks();
            automationLooper.runToEndOfTasks();
        }
    }

    private void advanceAutomationLooperScheduler(long millis) {
        ShadowLooper automationLooper = Shadows.shadowOf(automation.backgroundThread.getLooper());
        automationLooper.getScheduler().advanceBy(millis, TimeUnit.MILLISECONDS);
    }
}
