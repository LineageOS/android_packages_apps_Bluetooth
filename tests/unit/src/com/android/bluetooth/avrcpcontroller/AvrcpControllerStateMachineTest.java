/*
 * Copyright 2019 The Android Open Source Project
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package com.android.bluetooth.avrcpcontroller;

import static org.mockito.Mockito.*;

import android.bluetooth.BluetoothAdapter;
import android.bluetooth.BluetoothAvrcpController;
import android.bluetooth.BluetoothDevice;
import android.bluetooth.BluetoothProfile;
import android.content.Context;
import android.content.Intent;
import android.content.res.Resources;
import android.media.AudioManager;
import android.os.Looper;
import android.support.v4.media.session.MediaControllerCompat;
import android.support.v4.media.session.PlaybackStateCompat;

import androidx.test.InstrumentationRegistry;
import androidx.test.filters.MediumTest;
import androidx.test.rule.ServiceTestRule;
import androidx.test.runner.AndroidJUnit4;

import com.android.bluetooth.R;
import com.android.bluetooth.TestUtils;
import com.android.bluetooth.a2dpsink.A2dpSinkService;
import com.android.bluetooth.btservice.AdapterService;
import com.android.bluetooth.btservice.ProfileService;

import org.hamcrest.core.IsInstanceOf;
import org.junit.After;
import org.junit.Assert;
import org.junit.Assume;
import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.MockitoAnnotations;

import java.util.ArrayList;
import java.util.List;

@MediumTest
@RunWith(AndroidJUnit4.class)
public class AvrcpControllerStateMachineTest {
    private static final int ASYNC_CALL_TIMEOUT_MILLIS = 100;
    private static final int CONNECT_TIMEOUT_TEST_MILLIS = 1000;
    private static final int KEY_DOWN = 0;
    private static final int KEY_UP = 1;
    private BluetoothAdapter mAdapter;
    private AvrcpControllerStateMachine mAvrcpControllerStateMachine;
    private Context mTargetContext;
    private BluetoothDevice mTestDevice;
    private ArgumentCaptor<Intent> mIntentArgument = ArgumentCaptor.forClass(Intent.class);
    private byte[] mTestAddress = new byte[]{00, 01, 02, 03, 04, 05};

    @Rule public final ServiceTestRule mAvrcpServiceRule = new ServiceTestRule();
    @Rule public final ServiceTestRule mA2dpServiceRule = new ServiceTestRule();

    @Mock
    private AdapterService mAvrcpAdapterService;

    @Mock
    private AdapterService mA2dpAdapterService;

    @Mock
    private AudioManager mAudioManager;
    @Mock
    private AvrcpControllerService mAvrcpControllerService;

    @Mock
    private Resources mMockResources;

    AvrcpControllerStateMachine mAvrcpStateMachine;

    @Before
    public void setUp() throws Exception {
        mTargetContext = InstrumentationRegistry.getTargetContext();
        Assume.assumeTrue("Ignore test when AVRCP Controller is not enabled",
                mTargetContext.getResources().getBoolean(
                        R.bool.profile_supported_avrcp_controller));
        if (Looper.myLooper() == null) {
            Looper.prepare();
        }
        Assert.assertNotNull(Looper.myLooper());

        // Setup mocks and test assets
        MockitoAnnotations.initMocks(this);
        TestUtils.setAdapterService(mAvrcpAdapterService);
        TestUtils.startService(mAvrcpServiceRule, AvrcpControllerService.class);
        TestUtils.clearAdapterService(mAvrcpAdapterService);
        TestUtils.setAdapterService(mA2dpAdapterService);
        TestUtils.startService(mA2dpServiceRule, A2dpSinkService.class);
        when(mMockResources.getBoolean(R.bool.a2dp_sink_automatically_request_audio_focus))
                .thenReturn(true);
        doReturn(mMockResources).when(mAvrcpControllerService).getResources();
        doReturn(15).when(mAudioManager).getStreamMaxVolume(anyInt());
        doReturn(8).when(mAudioManager).getStreamVolume(anyInt());
        doReturn(true).when(mAudioManager).isVolumeFixed();
        doReturn(mAudioManager).when(mAvrcpControllerService)
                .getSystemService(Context.AUDIO_SERVICE);

        // This line must be called to make sure relevant objects are initialized properly
        mAdapter = BluetoothAdapter.getDefaultAdapter();
        // Get a device for testing
        mTestDevice = mAdapter.getRemoteDevice(mTestAddress);
        mAvrcpControllerService.start();
        mAvrcpControllerService.sBrowseTree = new BrowseTree(null);
        mAvrcpStateMachine = new AvrcpControllerStateMachine(mTestDevice, mAvrcpControllerService);
    }

    @After
    public void tearDown() throws Exception {
        if (!mTargetContext.getResources().getBoolean(R.bool.profile_supported_avrcp_controller)) {
            return;
        }
        TestUtils.clearAdapterService(mA2dpAdapterService);
    }

    /**
     * Test to confirm that the state machine is capable of cycling through the 4
     * connection states, and that upon completion, it cleans up aftwards.
     */
    @Test
    public void testDisconnect() {
        int numBroadcastsSent = setUpConnectedState(true, true);
        StackEvent event =
                StackEvent.connectionStateChanged(false, false);

        mAvrcpStateMachine.disconnect();
        numBroadcastsSent += 2;
        verify(mAvrcpControllerService,
                timeout(ASYNC_CALL_TIMEOUT_MILLIS).times(numBroadcastsSent)).sendBroadcast(
                mIntentArgument.capture(), eq(ProfileService.BLUETOOTH_PERM));
        Assert.assertEquals(mTestDevice, mIntentArgument.getValue().getParcelableExtra(
                BluetoothDevice.EXTRA_DEVICE));
        Assert.assertEquals(BluetoothAvrcpController.ACTION_CONNECTION_STATE_CHANGED,
                mIntentArgument.getValue().getAction());
        Assert.assertEquals(BluetoothProfile.STATE_DISCONNECTED,
                mIntentArgument.getValue().getIntExtra(BluetoothProfile.EXTRA_STATE, -1));
        Assert.assertThat(mAvrcpStateMachine.getCurrentState(),
                IsInstanceOf.instanceOf(AvrcpControllerStateMachine.Disconnected.class));
        Assert.assertEquals(mAvrcpStateMachine.getState(), BluetoothProfile.STATE_DISCONNECTED);
        verify(mAvrcpControllerService).removeStateMachine(eq(mAvrcpStateMachine));
        MediaControllerCompat.TransportControls transportControls =
                BluetoothMediaBrowserService.getTransportControls();
        Assert.assertEquals(PlaybackStateCompat.STATE_ERROR,
                BluetoothMediaBrowserService.getPlaybackState());
    }

    /**
     * Test to confirm that a control only device can be established (no browsing)
     */
    @Test
    public void testControlOnly() {
        int numBroadcastsSent = setUpConnectedState(true, false);
        MediaControllerCompat.TransportControls transportControls =
                BluetoothMediaBrowserService.getTransportControls();
        Assert.assertNotNull(transportControls);
        Assert.assertEquals(PlaybackStateCompat.STATE_NONE,
                BluetoothMediaBrowserService.getPlaybackState());
        StackEvent event =
                StackEvent.connectionStateChanged(false, false);
        mAvrcpStateMachine.disconnect();
        numBroadcastsSent += 2;
        verify(mAvrcpControllerService,
                timeout(ASYNC_CALL_TIMEOUT_MILLIS).times(numBroadcastsSent)).sendBroadcast(
                mIntentArgument.capture(), eq(ProfileService.BLUETOOTH_PERM));
        Assert.assertEquals(mTestDevice, mIntentArgument.getValue().getParcelableExtra(
                BluetoothDevice.EXTRA_DEVICE));
        Assert.assertEquals(BluetoothAvrcpController.ACTION_CONNECTION_STATE_CHANGED,
                mIntentArgument.getValue().getAction());
        Assert.assertEquals(BluetoothProfile.STATE_DISCONNECTED,
                mIntentArgument.getValue().getIntExtra(BluetoothProfile.EXTRA_STATE, -1));
        Assert.assertThat(mAvrcpStateMachine.getCurrentState(),
                IsInstanceOf.instanceOf(AvrcpControllerStateMachine.Disconnected.class));
        Assert.assertEquals(mAvrcpStateMachine.getState(), BluetoothProfile.STATE_DISCONNECTED);
        verify(mAvrcpControllerService).removeStateMachine(eq(mAvrcpStateMachine));
        Assert.assertEquals(PlaybackStateCompat.STATE_ERROR,
                BluetoothMediaBrowserService.getPlaybackState());
    }

    /**
     * Test to confirm that a browsing only device can be established (no control)
     */
    @Test
    public void testBrowsingOnly() {
        Assert.assertEquals(0, mAvrcpControllerService.sBrowseTree.mRootNode.getChildrenCount());
        int numBroadcastsSent = setUpConnectedState(false, true);
        Assert.assertEquals(1, mAvrcpControllerService.sBrowseTree.mRootNode.getChildrenCount());
        Assert.assertEquals(PlaybackStateCompat.STATE_NONE,
                BluetoothMediaBrowserService.getPlaybackState());
        StackEvent event =
                StackEvent.connectionStateChanged(false, false);
        mAvrcpStateMachine.disconnect();
        numBroadcastsSent += 2;
        verify(mAvrcpControllerService,
                timeout(ASYNC_CALL_TIMEOUT_MILLIS).times(numBroadcastsSent)).sendBroadcast(
                mIntentArgument.capture(), eq(ProfileService.BLUETOOTH_PERM));
        Assert.assertEquals(mTestDevice, mIntentArgument.getValue().getParcelableExtra(
                BluetoothDevice.EXTRA_DEVICE));
        Assert.assertEquals(BluetoothAvrcpController.ACTION_CONNECTION_STATE_CHANGED,
                mIntentArgument.getValue().getAction());
        Assert.assertEquals(BluetoothProfile.STATE_DISCONNECTED,
                mIntentArgument.getValue().getIntExtra(BluetoothProfile.EXTRA_STATE, -1));
        Assert.assertThat(mAvrcpStateMachine.getCurrentState(),
                IsInstanceOf.instanceOf(AvrcpControllerStateMachine.Disconnected.class));
        Assert.assertEquals(mAvrcpStateMachine.getState(), BluetoothProfile.STATE_DISCONNECTED);
        verify(mAvrcpControllerService).removeStateMachine(eq(mAvrcpStateMachine));
        MediaControllerCompat.TransportControls transportControls =
                BluetoothMediaBrowserService.getTransportControls();
        Assert.assertEquals(PlaybackStateCompat.STATE_ERROR,
                BluetoothMediaBrowserService.getPlaybackState());
    }

    /**
     * Test to make sure the state machine is tracking the correct device
     */
    @Test
    public void testGetDevice() {
        Assert.assertEquals(mAvrcpStateMachine.getDevice(), mTestDevice);
    }

    /**
     * Test that dumpsys will generate information about connected devices
     */
    @Test
    public void testDump() {
        StringBuilder sb = new StringBuilder();
        mAvrcpStateMachine.dump(sb);
        Assert.assertEquals(sb.toString(),
                "  mDevice: " + mTestDevice.toString()
                + "(null) name=AvrcpControllerStateMachine state=(null)\n");
    }

    /**
     * Test media browser play command
     */
    @Test
    public void testPlay() throws Exception {
        setUpConnectedState(true, true);
        MediaControllerCompat.TransportControls transportControls =
                BluetoothMediaBrowserService.getTransportControls();

        //Play
        transportControls.play();
        verify(mAvrcpControllerService,
                timeout(ASYNC_CALL_TIMEOUT_MILLIS).times(1)).sendPassThroughCommandNative(
                eq(mTestAddress), eq(AvrcpControllerService.PASS_THRU_CMD_ID_PLAY), eq(KEY_DOWN));
        verify(mAvrcpControllerService,
                timeout(ASYNC_CALL_TIMEOUT_MILLIS).times(1)).sendPassThroughCommandNative(
                eq(mTestAddress), eq(AvrcpControllerService.PASS_THRU_CMD_ID_PLAY), eq(KEY_UP));
    }

    /**
     * Test media browser pause command
     */
    @Test
    public void testPause() throws Exception {
        setUpConnectedState(true, true);
        MediaControllerCompat.TransportControls transportControls =
                BluetoothMediaBrowserService.getTransportControls();

        //Pause
        transportControls.pause();
        verify(mAvrcpControllerService,
                timeout(ASYNC_CALL_TIMEOUT_MILLIS).times(1)).sendPassThroughCommandNative(
                eq(mTestAddress), eq(AvrcpControllerService.PASS_THRU_CMD_ID_PAUSE), eq(KEY_DOWN));
        verify(mAvrcpControllerService,
                timeout(ASYNC_CALL_TIMEOUT_MILLIS).times(1)).sendPassThroughCommandNative(
                eq(mTestAddress), eq(AvrcpControllerService.PASS_THRU_CMD_ID_PAUSE), eq(KEY_UP));
    }

    /**
     * Test media browser stop command
     */
    @Test
    public void testStop() throws Exception {
        setUpConnectedState(true, true);
        MediaControllerCompat.TransportControls transportControls =
                BluetoothMediaBrowserService.getTransportControls();

        //Stop
        transportControls.stop();
        verify(mAvrcpControllerService,
                timeout(ASYNC_CALL_TIMEOUT_MILLIS).times(1)).sendPassThroughCommandNative(
                eq(mTestAddress), eq(AvrcpControllerService.PASS_THRU_CMD_ID_STOP), eq(KEY_DOWN));
        verify(mAvrcpControllerService,
                timeout(ASYNC_CALL_TIMEOUT_MILLIS).times(1)).sendPassThroughCommandNative(
                eq(mTestAddress), eq(AvrcpControllerService.PASS_THRU_CMD_ID_STOP), eq(KEY_UP));
    }

    /**
     * Test media browser next command
     */
    @Test
    public void testNext() throws Exception {
        setUpConnectedState(true, true);
        MediaControllerCompat.TransportControls transportControls =
                BluetoothMediaBrowserService.getTransportControls();

        //Next
        transportControls.skipToNext();
        verify(mAvrcpControllerService,
                timeout(ASYNC_CALL_TIMEOUT_MILLIS).times(1)).sendPassThroughCommandNative(
                eq(mTestAddress), eq(AvrcpControllerService.PASS_THRU_CMD_ID_FORWARD),
                eq(KEY_DOWN));
        verify(mAvrcpControllerService,
                timeout(ASYNC_CALL_TIMEOUT_MILLIS).times(1)).sendPassThroughCommandNative(
                eq(mTestAddress), eq(AvrcpControllerService.PASS_THRU_CMD_ID_FORWARD), eq(KEY_UP));
    }

    /**
     * Test media browser previous command
     */
    @Test
    public void testPrevious() throws Exception {
        setUpConnectedState(true, true);
        MediaControllerCompat.TransportControls transportControls =
                BluetoothMediaBrowserService.getTransportControls();

        //Previous
        transportControls.skipToPrevious();
        verify(mAvrcpControllerService,
                timeout(ASYNC_CALL_TIMEOUT_MILLIS).times(1)).sendPassThroughCommandNative(
                eq(mTestAddress), eq(AvrcpControllerService.PASS_THRU_CMD_ID_BACKWARD),
                eq(KEY_DOWN));
        verify(mAvrcpControllerService,
                timeout(ASYNC_CALL_TIMEOUT_MILLIS).times(1)).sendPassThroughCommandNative(
                eq(mTestAddress), eq(AvrcpControllerService.PASS_THRU_CMD_ID_BACKWARD), eq(KEY_UP));
    }

    /**
     * Test media browser fast forward command
     */
    @Test
    public void testFastForward() throws Exception {
        setUpConnectedState(true, true);
        MediaControllerCompat.TransportControls transportControls =
                BluetoothMediaBrowserService.getTransportControls();

        //FastForward
        transportControls.fastForward();
        verify(mAvrcpControllerService,
                timeout(ASYNC_CALL_TIMEOUT_MILLIS).times(1)).sendPassThroughCommandNative(
                eq(mTestAddress), eq(AvrcpControllerService.PASS_THRU_CMD_ID_FF), eq(KEY_DOWN));
        //Finish FastForwarding
        transportControls.play();
        verify(mAvrcpControllerService,
                timeout(ASYNC_CALL_TIMEOUT_MILLIS).times(1)).sendPassThroughCommandNative(
                eq(mTestAddress), eq(AvrcpControllerService.PASS_THRU_CMD_ID_FF), eq(KEY_UP));
    }

    /**
     * Test media browser rewind command
     */
    @Test
    public void testRewind() throws Exception {
        setUpConnectedState(true, true);
        MediaControllerCompat.TransportControls transportControls =
                BluetoothMediaBrowserService.getTransportControls();

        //Rewind
        transportControls.rewind();
        verify(mAvrcpControllerService,
                timeout(ASYNC_CALL_TIMEOUT_MILLIS).times(1)).sendPassThroughCommandNative(
                eq(mTestAddress), eq(AvrcpControllerService.PASS_THRU_CMD_ID_REWIND), eq(KEY_DOWN));
        //Finish Rewinding
        transportControls.play();
        verify(mAvrcpControllerService,
                timeout(ASYNC_CALL_TIMEOUT_MILLIS).times(1)).sendPassThroughCommandNative(
                eq(mTestAddress), eq(AvrcpControllerService.PASS_THRU_CMD_ID_REWIND), eq(KEY_UP));
    }

    /**
     * Test media browser skip to queue item
     */
    @Test
    public void testSkipToQueueInvalid() throws Exception {
        byte scope = 1;
        int minSize = 0;
        int maxSize = 255;
        setUpConnectedState(true, true);
        MediaControllerCompat.TransportControls transportControls =
                BluetoothMediaBrowserService.getTransportControls();

        //Play an invalid item below start
        transportControls.skipToQueueItem(minSize - 1);
        verify(mAvrcpControllerService,
                timeout(ASYNC_CALL_TIMEOUT_MILLIS).times(0)).playItemNative(
                eq(mTestAddress), eq(scope), anyLong(), anyInt());

        //Play an invalid item beyond end
        transportControls.skipToQueueItem(maxSize + 1);
        verify(mAvrcpControllerService,
                timeout(ASYNC_CALL_TIMEOUT_MILLIS).times(0)).playItemNative(
                eq(mTestAddress), eq(scope), anyLong(), anyInt());
    }

    /**
     * Test media browser shuffle command
     */
    @Test
    public void testShuffle() throws Exception {
        byte[] shuffleSetting = new byte[]{3};
        byte[] shuffleMode = new byte[]{2};

        setUpConnectedState(true, true);
        MediaControllerCompat.TransportControls transportControls =
                BluetoothMediaBrowserService.getTransportControls();

        //Shuffle
        transportControls.setShuffleMode(1);
        verify(mAvrcpControllerService, timeout(ASYNC_CALL_TIMEOUT_MILLIS).times(1))
                .setPlayerApplicationSettingValuesNative(
                eq(mTestAddress), eq((byte) 1), eq(shuffleSetting), eq(shuffleMode));
    }

    /**
     * Test media browser repeat command
     */
    @Test
    public void testRepeat() throws Exception {
        byte[] repeatSetting = new byte[]{2};
        byte[] repeatMode = new byte[]{3};

        setUpConnectedState(true, true);
        MediaControllerCompat.TransportControls transportControls =
                BluetoothMediaBrowserService.getTransportControls();

        //Shuffle
        transportControls.setRepeatMode(2);
        verify(mAvrcpControllerService, timeout(ASYNC_CALL_TIMEOUT_MILLIS).times(1))
                .setPlayerApplicationSettingValuesNative(
                eq(mTestAddress), eq((byte) 1), eq(repeatSetting), eq(repeatMode));
    }

    /**
     * Test media browsing
     * Verify that a browse tree is created with the proper root
     * Verify that a player can be fetched and added to the browse tree
     * Verify that the contents of a player are fetched upon request
     */
    @Test
    public void testBrowsingCommands() {
        setUpConnectedState(true, true);
        final String rootName = "__ROOT__";
        final String playerName = "Player 1";

        //Get the root of the device
        BrowseTree.BrowseNode results = mAvrcpStateMachine.findNode(rootName);
        Assert.assertEquals(rootName + mTestDevice.toString(), results.getID());

        //Request fetch the list of players
        BrowseTree.BrowseNode playerNodes = mAvrcpStateMachine.findNode(results.getID());
        mAvrcpStateMachine.requestContents(results);
        verify(mAvrcpControllerService,
                timeout(ASYNC_CALL_TIMEOUT_MILLIS).times(1)).getPlayerListNative(eq(mTestAddress),
                eq(0), eq(19));

        //Provide back a player object
        byte[] playerFeatures =
                new byte[]{0, 0, 0, 0, 0, (byte) 0xb7, 0x01, 0x0c, 0x0a, 0, 0, 0, 0, 0, 0, 0};
        AvrcpPlayer playerOne = new AvrcpPlayer(1, playerName, playerFeatures, 1, 1);
        List<AvrcpPlayer> testPlayers = new ArrayList<>();
        testPlayers.add(playerOne);
        mAvrcpStateMachine.sendMessage(AvrcpControllerStateMachine.MESSAGE_PROCESS_GET_PLAYER_ITEMS,
                testPlayers);

        //Verify that the player object is available.
        mAvrcpStateMachine.requestContents(results);
        verify(mAvrcpControllerService,
                timeout(ASYNC_CALL_TIMEOUT_MILLIS).times(1)).getPlayerListNative(eq(mTestAddress),
                eq(1), eq(0));
        mAvrcpStateMachine.sendMessage(
                AvrcpControllerStateMachine.MESSAGE_PROCESS_GET_FOLDER_ITEMS_OUT_OF_RANGE);
        playerNodes = mAvrcpStateMachine.findNode(results.getID());
        Assert.assertEquals(true, results.isCached());
        Assert.assertEquals("MediaItem{mFlags=1, mDescription=" + playerName + ", null, null}",
                results.getChildren().get(0).getMediaItem().toString());

        //Fetch contents of that player object
        BrowseTree.BrowseNode playerOneNode = mAvrcpStateMachine.findNode(
                results.getChildren().get(0).getID());
        mAvrcpStateMachine.requestContents(playerOneNode);
        verify(mAvrcpControllerService,
                timeout(ASYNC_CALL_TIMEOUT_MILLIS).times(1)).setBrowsedPlayerNative(
                eq(mTestAddress), eq(1));
        mAvrcpStateMachine.sendMessage(AvrcpControllerStateMachine.MESSAGE_PROCESS_FOLDER_PATH, 5);
        verify(mAvrcpControllerService,
                timeout(ASYNC_CALL_TIMEOUT_MILLIS).times(1)).getFolderListNative(eq(mTestAddress),
                eq(0), eq(4));
    }

    /**
     * Test addressed media player changed
     * Verify when the addressed media player changes browsing data updates
     * Verify that the contents of a player are fetched upon request
     */
    @Test
    public void testPlayerChanged() {
        setUpConnectedState(true, true);
        final String rootName = "__ROOT__";
        final String playerName = "Player 1";

        //Get the root of the device
        BrowseTree.BrowseNode results = mAvrcpStateMachine.findNode(rootName);
        Assert.assertEquals(rootName + mTestDevice.toString(), results.getID());

        //Request fetch the list of players
        BrowseTree.BrowseNode playerNodes = mAvrcpStateMachine.findNode(results.getID());
        mAvrcpStateMachine.requestContents(results);
        verify(mAvrcpControllerService,
                timeout(ASYNC_CALL_TIMEOUT_MILLIS).times(1)).getPlayerListNative(eq(mTestAddress),
                eq(0), eq(19));

        //Provide back a player object
        byte[] playerFeatures =
                new byte[]{0, 0, 0, 0, 0, (byte) 0xb7, 0x01, 0x0c, 0x0a, 0, 0, 0, 0, 0, 0, 0};
        AvrcpPlayer playerOne = new AvrcpPlayer(1, playerName, playerFeatures, 1, 1);
        List<AvrcpPlayer> testPlayers = new ArrayList<>();
        testPlayers.add(playerOne);
        mAvrcpStateMachine.sendMessage(AvrcpControllerStateMachine.MESSAGE_PROCESS_GET_PLAYER_ITEMS,
                testPlayers);

        //Change players and verify that BT attempts to update the results
        mAvrcpStateMachine.sendMessage(
                AvrcpControllerStateMachine.MESSAGE_PROCESS_ADDRESSED_PLAYER_CHANGED, 4);
        results = mAvrcpStateMachine.findNode(rootName);

        mAvrcpStateMachine.requestContents(results);

        verify(mAvrcpControllerService,
                timeout(ASYNC_CALL_TIMEOUT_MILLIS).times(2)).getPlayerListNative(eq(mTestAddress),
                eq(0), eq(19));
    }

    /**
     * Test that the Now Playing playlist is updated when it changes.
     */
    @Test
    public void testNowPlaying() {
        setUpConnectedState(true, true);
        mAvrcpStateMachine.nowPlayingContentChanged();
        verify(mAvrcpControllerService,
                timeout(ASYNC_CALL_TIMEOUT_MILLIS).times(1)).getNowPlayingListNative(
                eq(mTestAddress), eq(0), eq(19));
    }

    /**
     * Test that AVRCP events such as playback commands can execute while performing browsing.
     */
    @Test
    public void testPlayWhileBrowsing() {
        setUpConnectedState(true, true);
        final String rootName = "__ROOT__";
        final String playerName = "Player 1";

        //Get the root of the device
        BrowseTree.BrowseNode results = mAvrcpStateMachine.findNode(rootName);
        Assert.assertEquals(rootName + mTestDevice.toString(), results.getID());

        //Request fetch the list of players
        BrowseTree.BrowseNode playerNodes = mAvrcpStateMachine.findNode(results.getID());
        mAvrcpStateMachine.requestContents(results);

        MediaControllerCompat.TransportControls transportControls =
                BluetoothMediaBrowserService.getTransportControls();
        transportControls.play();
        verify(mAvrcpControllerService,
                timeout(ASYNC_CALL_TIMEOUT_MILLIS).times(1)).sendPassThroughCommandNative(
                eq(mTestAddress), eq(AvrcpControllerService.PASS_THRU_CMD_ID_PLAY), eq(KEY_DOWN));
        verify(mAvrcpControllerService,
                timeout(ASYNC_CALL_TIMEOUT_MILLIS).times(1)).sendPassThroughCommandNative(
                eq(mTestAddress), eq(AvrcpControllerService.PASS_THRU_CMD_ID_PLAY), eq(KEY_UP));
    }

    /**
     * Test that Absolute Volume Registration is working
     */
    @Test
    public void testRegisterAbsVolumeNotification() {
        setUpConnectedState(true, true);
        mAvrcpStateMachine.sendMessage(
                AvrcpControllerStateMachine.MESSAGE_PROCESS_REGISTER_ABS_VOL_NOTIFICATION);
        verify(mAvrcpControllerService, timeout(ASYNC_CALL_TIMEOUT_MILLIS).times(1))
                .sendRegisterAbsVolRspNative(any(), anyByte(), eq(127), anyInt());
    }

    /**
     * Test playback does not request focus when another app is playing music.
     */
    @Test
    public void testPlaybackWhileMusicPlaying() {
        when(mMockResources.getBoolean(R.bool.a2dp_sink_automatically_request_audio_focus))
                .thenReturn(false);
        Assert.assertEquals(AudioManager.AUDIOFOCUS_NONE, A2dpSinkService.getFocusState());
        doReturn(true).when(mAudioManager).isMusicActive();
        setUpConnectedState(true, true);
        mAvrcpStateMachine.sendMessage(
                AvrcpControllerStateMachine.MESSAGE_PROCESS_PLAY_STATUS_CHANGED,
                PlaybackStateCompat.STATE_PLAYING);
        TestUtils.waitForLooperToFinishScheduledTask(mAvrcpStateMachine.getHandler().getLooper());
        verify(mAvrcpControllerService,
                timeout(ASYNC_CALL_TIMEOUT_MILLIS).times(1)).sendPassThroughCommandNative(
                eq(mTestAddress), eq(AvrcpControllerService.PASS_THRU_CMD_ID_PAUSE), eq(KEY_DOWN));
        TestUtils.waitForLooperToFinishScheduledTask(
                A2dpSinkService.getA2dpSinkService().getMainLooper());
        Assert.assertEquals(AudioManager.AUDIOFOCUS_NONE, A2dpSinkService.getFocusState());
    }

    /**
     * Test playback requests focus while nothing is playing music.
     */
    @Test
    public void testPlaybackWhileIdle() {
        Assert.assertEquals(AudioManager.AUDIOFOCUS_NONE, A2dpSinkService.getFocusState());
        doReturn(false).when(mAudioManager).isMusicActive();
        setUpConnectedState(true, true);
        mAvrcpStateMachine.sendMessage(
                AvrcpControllerStateMachine.MESSAGE_PROCESS_PLAY_STATUS_CHANGED,
                PlaybackStateCompat.STATE_PLAYING);
        TestUtils.waitForLooperToFinishScheduledTask(mAvrcpStateMachine.getHandler().getLooper());
        TestUtils.waitForLooperToFinishScheduledTask(
                A2dpSinkService.getA2dpSinkService().getMainLooper());
        Assert.assertEquals(AudioManager.AUDIOFOCUS_GAIN, A2dpSinkService.getFocusState());
    }

    /**
     * Setup Connected State
     *
     * @return number of times mAvrcpControllerService.sendBroadcastAsUser() has been invoked
     */
    private int setUpConnectedState(boolean control, boolean browsing) {
        // Put test state machine into connected state
        mAvrcpStateMachine.start();
        Assert.assertThat(mAvrcpStateMachine.getCurrentState(),
                IsInstanceOf.instanceOf(AvrcpControllerStateMachine.Disconnected.class));

        mAvrcpStateMachine.connect(StackEvent.connectionStateChanged(control, browsing));
        verify(mAvrcpControllerService, timeout(ASYNC_CALL_TIMEOUT_MILLIS).times(2)).sendBroadcast(
                mIntentArgument.capture(), eq(ProfileService.BLUETOOTH_PERM));
        Assert.assertThat(mAvrcpStateMachine.getCurrentState(),
                IsInstanceOf.instanceOf(AvrcpControllerStateMachine.Connected.class));
        Assert.assertEquals(mAvrcpStateMachine.getState(), BluetoothProfile.STATE_CONNECTED);

        return BluetoothProfile.STATE_CONNECTED;
    }

}
