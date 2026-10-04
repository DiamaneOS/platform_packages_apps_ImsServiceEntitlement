/*
 * Copyright (C) 2021 The Android Open Source Project
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

package com.android.imsserviceentitlement;

import com.android.imsserviceentitlement.utils.HttpsUrl;


import android.annotation.SuppressLint;
import android.app.Activity;
import android.graphics.Bitmap;
import android.os.Bundle;
import android.util.Log;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.webkit.WebSettings;
import android.webkit.WebView;
import android.webkit.WebViewClient;
import android.widget.ProgressBar;

import com.android.imsserviceentitlement.R;

import androidx.fragment.app.Fragment;
import java.util.Set;
import com.android.imsserviceentitlement.utils.PortalBridge;

/** A fragment of WebView to render emergency address web portal */
public class WfcQnsWebPortalFragment extends Fragment {
  private static final String TAG = "IMSSE-WfcQnsWebPortalFragment";

  private static final String URL_KEY = "URL_KEY";
  private PortalBridge mBridge;
  private WebView mWebView;

  /** Public static constructor */
  public static WfcQnsWebPortalFragment newInstance(String url) {
    WfcQnsWebPortalFragment frag = new WfcQnsWebPortalFragment();

    Bundle args = new Bundle();
    args.putString(URL_KEY, url);
    frag.setArguments(args);

    return frag;
  }

  @SuppressLint("SetJavaScriptEnabled") // only trusted URLs are loaded
  @Override
  public View onCreateView(
      LayoutInflater inflater, ViewGroup container, Bundle savedInstanceState) {
    View v = inflater.inflate(R.layout.fragment_webview, container, false);

    Bundle arguments = getArguments();
    String url = arguments == null ? null : arguments.getString(URL_KEY);
    if (!HttpsUrl.isAllowed(url)) {
      if (getActivity() != null) {
        getActivity().setResult(Activity.RESULT_CANCELED);
        getActivity().finish();
      }
      return v;
    }

    ProgressBar spinner = (ProgressBar) v.findViewById(R.id.loadingbar);
    WebView webView = (WebView) v.findViewById(R.id.webview);
    mWebView = webView;
    webView.setWebViewClient(
        new WebViewClient() {
          private boolean hideLoader = false;

          @Override
          public boolean shouldOverrideUrlLoading(WebView view, String url) {
            Log.d(TAG, "shouldOverrideUrlLoading()");
            return !HttpsUrl.isAllowed(url); // Block non-HTTPS redirects
          }

          @Override
          public void onPageStarted(WebView webview, String url, Bitmap favicon) {
            // Webview will be invisible for first time only
            Log.d(TAG, "onPageStarted()");
            if (!hideLoader) {
              webview.setVisibility(WebView.INVISIBLE);
              Log.d(TAG, "onPageStarted() setVisibility(WebView.INVISIBLE)");
            }
          }

          @Override
          public void onPageFinished(WebView view, String url) {
            Log.d(TAG, "onPageFinished()");
            hideLoader = true;
            spinner.setVisibility(View.GONE);
            view.setVisibility(WebView.VISIBLE);
            super.onPageFinished(view, url);
          }
        });

    mBridge = PortalBridge.install(webView, url, "WiFiCallingWebViewController",
        Set.of("cancelButtonClicked", "cancelButtonPressed", "phoneServicesAccountStatusChanged",
            "CloseWebView"), method -> {
          if (method.equals("cancelButtonClicked") || method.equals("CloseWebView")) {
            Activity activity = getActivity();
            if (activity != null) { activity.setResult(Activity.RESULT_CANCELED); activity.finish(); }
          }
        });
    if (mBridge == null) {
      Activity activity = getActivity();
      if (activity != null) { activity.setResult(Activity.RESULT_CANCELED); activity.finish(); }
      return v;
    }

    WebSettings settings = webView.getSettings();
    settings.setDomStorageEnabled(true);
    settings.setJavaScriptEnabled(true);
    settings.setAllowFileAccess(false);
    settings.setAllowContentAccess(false);
    settings.setMixedContentMode(WebSettings.MIXED_CONTENT_NEVER_ALLOW);

    webView.loadUrl(url);

    return v;
  }

  @Override
  public void onDestroyView() {
    if (mBridge != null) { mBridge.close(); mBridge = null; }
    if (mWebView != null) {
      mWebView.stopLoading();
      mWebView.setWebViewClient(new WebViewClient());
      mWebView.destroy();
      mWebView = null;
    }
    super.onDestroyView();
  }
}
