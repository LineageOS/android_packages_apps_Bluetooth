/*
 * Copyright 2024 The Android Open Source Project
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
package com.android.bluetooth.opp;

import static android.os.UserHandle.myUserId;

import static com.google.common.truth.Truth.assertThat;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.spy;

import android.content.ContentResolver;
import android.content.Context;
import android.content.IContentProvider;
import android.content.pm.ApplicationInfo;
import android.content.res.AssetFileDescriptor;
import android.database.MatrixCursor;
import android.net.Uri;
import android.os.Build;
import android.provider.OpenableColumns;
import android.test.mock.MockContentProvider;
import android.test.mock.MockContext;

import androidx.test.runner.AndroidJUnit4;

import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;

import java.io.FileInputStream;

@RunWith(AndroidJUnit4.class)
public class BluetoothOppSendFileInfoTest {
    public static final String PROVIDER_NAME_MEDIA = "media";

    TestContext mContext;
    TestContentResolver mContentResolver;
    MockContentProvider mContentProvider;
    MatrixCursor mCursor;

    private static Uri buildContentUriWithEncodedAuthority(String authority) {
        return new Uri.Builder().scheme("content")
                .encodedAuthority(authority)
                .path("external/images/media/1")
                .build();
    }

    @Before
    public void setUp() {
        mContext = new TestContext();
        mContentResolver = mContext.getContentResolver();
        mContentProvider = mContext.getContentProvider();
    }

    @Test
    public void generateFileInfo_withContentUriForOtherUser_returnsSendFileInfoError()
            throws Exception {
        String type = "image/jpeg";
        Uri uri = buildContentUriWithEncodedAuthority((myUserId() + 1) + "@" + PROVIDER_NAME_MEDIA);
        doReturn(type).when(mContentProvider).getType(any());

        long fileLength = 1000;
        String fileName = "pic.jpg";

        FileInputStream fs = mock(FileInputStream.class);
        AssetFileDescriptor fd = mock(AssetFileDescriptor.class);
        doReturn(fileLength).when(fd).getLength();
        doReturn(fs).when(fd).createInputStream();

        doReturn(fd).when(mContentProvider).openAssetFile(eq(uri), any(), any());

        mCursor =
                new MatrixCursor(new String[]{OpenableColumns.DISPLAY_NAME, OpenableColumns.SIZE});
        mCursor.addRow(new Object[]{fileName, fileLength});

        doReturn(mCursor).when(mContentProvider).query(eq(uri), any(), any(), any(), any());

        BluetoothOppSendFileInfo info =
                BluetoothOppSendFileInfo.generateFileInfo(mContext, uri, type, true);

        assertThat(info).isEqualTo(BluetoothOppSendFileInfo.SEND_FILE_INFO_ERROR);
    }

    @Test
    public void generateFileInfo_withContentUriForImplicitUser_returnsInfoWithCorrectLength()
            throws Exception {
        String type = "image/jpeg";
        Uri uri = buildContentUriWithEncodedAuthority(PROVIDER_NAME_MEDIA);
        doReturn(type).when(mContentProvider).getType(any());

        long fileLength = 1000;
        String fileName = "pic.jpg";

        FileInputStream fs = mock(FileInputStream.class);
        AssetFileDescriptor fd = mock(AssetFileDescriptor.class);
        doReturn(fileLength).when(fd).getLength();
        doReturn(fs).when(fd).createInputStream();

        doReturn(fd).when(mContentProvider).openAssetFile(eq(uri), any(), any());

        mCursor =
                new MatrixCursor(new String[]{OpenableColumns.DISPLAY_NAME, OpenableColumns.SIZE});
        mCursor.addRow(new Object[]{fileName, fileLength});

        doReturn(mCursor).when(mContentProvider).query(eq(uri), any(), any(), any(), any());

        BluetoothOppSendFileInfo info =
                BluetoothOppSendFileInfo.generateFileInfo(mContext, uri, type, true);

        assertThat(info.mInputStream).isEqualTo(fs);
        assertThat(info.mFileName).isEqualTo(fileName);
        assertThat(info.mLength).isEqualTo(fileLength);
        assertThat(info.mStatus).isEqualTo(0);
    }

    @Test
    public void generateFileInfo_withContentUriForSameUser_returnsInfoWithCorrectLength()
            throws Exception {
        String type = "image/jpeg";
        Uri uri = buildContentUriWithEncodedAuthority(myUserId() + "@" + PROVIDER_NAME_MEDIA);
        doReturn(type).when(mContentProvider).getType(any());

        long fileLength = 1000;
        String fileName = "pic.jpg";

        FileInputStream fs = mock(FileInputStream.class);
        AssetFileDescriptor fd = mock(AssetFileDescriptor.class);
        doReturn(fileLength).when(fd).getLength();
        doReturn(fs).when(fd).createInputStream();

        doReturn(fd).when(mContentProvider).openAssetFile(eq(uri), any(), any());

        mCursor =
                new MatrixCursor(new String[]{OpenableColumns.DISPLAY_NAME, OpenableColumns.SIZE});
        mCursor.addRow(new Object[]{fileName, fileLength});

        doReturn(mCursor).when(mContentProvider).query(eq(uri), any(), any(), any(), any());

        BluetoothOppSendFileInfo info =
                BluetoothOppSendFileInfo.generateFileInfo(mContext, uri, type, true);

        assertThat(info.mInputStream).isEqualTo(fs);
        assertThat(info.mFileName).isEqualTo(fileName);
        assertThat(info.mLength).isEqualTo(fileLength);
        assertThat(info.mStatus).isEqualTo(0);
    }

    public static final class TestContext extends MockContext {

        private final TestContentResolver mContentResolver;
        private final MockContentProvider mContentProvider;

        public TestContext() {
            mContentProvider = spy(new MockContentProvider(this));
            mContentResolver = new TestContentResolver(this, mContentProvider);
        }

        @Override
        public TestContentResolver getContentResolver() {
            return mContentResolver;
        }

        public MockContentProvider getContentProvider() {
            return mContentProvider;
        }

        @Override
        public String getOpPackageName() {
            return "test.package";
        }

        @Override
        public ApplicationInfo getApplicationInfo() {
            ApplicationInfo applicationInfo = new ApplicationInfo();
            applicationInfo.targetSdkVersion = Build.VERSION.SDK_INT;
            return applicationInfo;
        }
    }

    public static final class TestContentResolver extends ContentResolver {

        private final MockContentProvider mContentProvider;

        public TestContentResolver(Context context, MockContentProvider contentProvider) {
            super(context, contentProvider);
            mContentProvider = contentProvider;
        }

        @Override
        protected IContentProvider acquireProvider(Context c, String name) {
            return mContentProvider.getIContentProvider();
        }

        @Override
        public boolean releaseProvider(IContentProvider icp) {
            return true;
        }

        @Override
        protected IContentProvider acquireUnstableProvider(Context c, String name) {
            return mContentProvider.getIContentProvider();
        }

        @Override
        public boolean releaseUnstableProvider(IContentProvider icp) {
            return true;
        }

        @Override
        public void unstableProviderDied(IContentProvider icp) {
        }
    }
}
