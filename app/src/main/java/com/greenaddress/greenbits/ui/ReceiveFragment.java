package com.greenaddress.greenbits.ui;


import android.app.Activity;
import android.app.Dialog;
import android.content.ClipData;
import android.content.ClipboardManager;
import android.content.Context;
import android.content.Intent;
import android.graphics.drawable.BitmapDrawable;
import android.nfc.Tag;
import android.os.Bundle;
import android.text.Html;
import android.util.DisplayMetrics;
import android.util.Log;
import android.util.TypedValue;
import android.view.LayoutInflater;
import android.view.Menu;
import android.view.MenuInflater;
import android.view.MenuItem;
import android.view.View;
import android.view.ViewGroup;
import android.view.Window;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.TextView;

import com.google.common.util.concurrent.FutureCallback;
import com.google.common.util.concurrent.Futures;
import com.greenaddress.greenapi.Network;
import com.greenaddress.greenbits.QrBitmap;

import org.bitcoinj.core.Address;
import org.bitcoinj.uri.BitcoinURI;

import nordpol.android.OnDiscoveredTagListener;
import nordpol.android.TagDispatcher;


public class ReceiveFragment extends SubaccountFragment implements OnDiscoveredTagListener {
    private static final String TAG = ReceiveFragment.class.getSimpleName();

    private View mView;
    private FutureCallback<QrBitmap> onAddress = null;
    private QrBitmap address = null;
    private int curSubaccount;
    private boolean pausing = false;
    private Dialog qrDialog;
    private TagDispatcher tagDispatcher;

    @Override
    public void onSaveInstanceState(final Bundle outState) {
        super.onSaveInstanceState(outState);

        outState.putBoolean("pausing", pausing);
        if (address != null) {
            outState.putParcelable("address", address);
        }
    }

    @Override
    public void setUserVisibleHint(final boolean isVisibleToUser) {
        super.setUserVisibleHint(isVisibleToUser);
        final View v = getView(); // FIXME: This should use mView

        if (!pausing && v != null) {
            // get a new address every time the tab is displayed
            if (isVisibleToUser) {
                hideKeyboard();
                // get a new address:
                if (address == null && !setting_qrcode)
                    getNewAddress(v);
            } else { // !isVisibleToUser
                // hide to avoid showing old address when swiping
                final TextView receiveAddress = UI.find(v, R.id.receiveAddressText);
                final ImageView imageView = UI.find(v, R.id.receiveQrImageView);
                address = null;
                receiveAddress.setText("");
                imageView.setImageBitmap(null);
            }
        }
        if (isVisibleToUser)
            pausing = false;
    }

    boolean setting_qrcode = false;

    @Override
    public void onResume() {
        super.onResume();
        if (onAddress != null && address == null && !setting_qrcode)
            getNewAddress(null);
    }

    @Override
    public void onPause() {
        super.onPause();

        if (getUserVisibleHint()) {
            pausing = true;
        }
        
        tagDispatcher.disableExclusiveNfc();
    }

    @Override
    public View onCreateView(final LayoutInflater inflater, final ViewGroup container,
                             final Bundle savedInstanceState) {
        final GaActivity gaActivity = getGaActivity();

        registerReceiver();

        if (savedInstanceState != null) {
            pausing = savedInstanceState.getBoolean("pausing");
            address = savedInstanceState.getParcelable("address");
        }
        
        tagDispatcher = TagDispatcher.get(getActivity(), this);
        tagDispatcher.enableExclusiveNfc();

        curSubaccount = getGAService().getCurrentSubAccount();

        mView = inflater.inflate(R.layout.fragment_receive, container, false);
        final TextView receiveAddress = UI.find(mView, R.id.receiveAddressText);
        final TextView copyIcon = UI.find(mView, R.id.receiveCopyIcon);
        final TextView copyText = UI.find(mView, R.id.receiveCopyText);
        UI.hide(copyIcon, copyText);

        final TextView newAddressIcon = UI.find(mView, R.id.receiveNewAddressIcon);
        final ImageView imageView = UI.find(mView, R.id.receiveQrImageView);
        copyIcon.setOnClickListener(
                new View.OnClickListener() {
                    @Override
                    public void onClick(final View v) {
                        // Gets a handle to the clipboard service.
                        final ClipboardManager clipboard = (ClipboardManager)
                                getActivity().getSystemService(Context.CLIPBOARD_SERVICE);
                        final ClipData clip = ClipData.newPlainText("data", UI.getText(receiveAddress).replace("\n", ""));
                        clipboard.setPrimaryClip(clip);

                        final String text = gaActivity.getString(R.string.toastOnCopyAddress) + " " + gaActivity.getString(R.string.warnOnPaste);
                        gaActivity.toast(text);
                    }
                }
        );
        final View qrView = getActivity().getLayoutInflater().inflate(R.layout.dialog_qrcode, null, false);

        final ImageView qrcodeInDialog = UI.find(qrView, R.id.qrInDialogImageView);
        onAddress = new FutureCallback<QrBitmap>() {
            @Override
            public void onSuccess(final QrBitmap result) {
                address = result;

                final Activity activity = getActivity();
                if (activity == null)
                    return;

                activity.runOnUiThread(new Runnable() {
                    public void run() {
                        UI.show(copyIcon, copyText);
                        stopNewAddressAnimation(mView);
                        final BitmapDrawable bd = new BitmapDrawable(getResources(), result.getQRCode());
                        bd.setFilterBitmap(false);
                        imageView.setImageDrawable(bd);

                        final String qrData = result.getData();
                        receiveAddress.setText(String.format("%s\n%s\n%s", qrData.substring(0, 12), qrData.substring(12, 24), qrData.substring(24)));
                        setting_qrcode = false;

                        imageView.setOnClickListener(new View.OnClickListener() {
                            public void onClick(final View v) {
                                if (qrDialog == null) {
                                    final DisplayMetrics displaymetrics = new DisplayMetrics();
                                    activity.getWindowManager().getDefaultDisplay().getMetrics(displaymetrics);
                                    final int height = displaymetrics.heightPixels;
                                    final int width = displaymetrics.widthPixels;
                                    Log.i(TAG, height + "x" + width);
                                    final int min = (int) (Math.min(height, width) * 0.8);
                                    final LinearLayout.LayoutParams layoutParams = new LinearLayout.LayoutParams(min, min);
                                    qrcodeInDialog.setLayoutParams(layoutParams);

                                    qrDialog = new Dialog(activity);
                                    qrDialog.getWindow().requestFeature(Window.FEATURE_NO_TITLE);
                                    qrDialog.setContentView(qrView);
                                }
                                qrDialog.show();
                                final BitmapDrawable bd = new BitmapDrawable(getResources(), result.getQRCode());
                                bd.setFilterBitmap(false);
                                qrcodeInDialog.setImageDrawable(bd);
                            }
                        });
                    }
                });
            }

            @Override
            public void onFailure(final Throwable t) {
                t.printStackTrace();
                final Activity activity = getActivity();
                if (activity == null)
                    return;

                activity.runOnUiThread(new Runnable() {
                    public void run() {
                        stopNewAddressAnimation(mView);
                        UI.show(copyIcon, copyText);
                    }
                });
            }
        };

        if (address != null) {
            onAddress.onSuccess(address);
        }

        newAddressIcon.setOnClickListener(
                new View.OnClickListener() {
                    @Override
                    public void onClick(final View v) {
                        if (!setting_qrcode) {
                            // FIXME: Instead of checking the state here, enable/disable sendButton when state changes
                            if (!getGAApp().mService.isLoggedIn()) {
                                gaActivity.toast(R.string.err_send_not_connected_will_resume);
                                return;
                            }
                            getNewAddress(mView);
                        }
                    }
                }
        );

        return mView;
    }

    private void getNewAddress(final View v) {
        setting_qrcode = true;

        if (v != null)
            startNewAddressAnimation(v);

        Futures.addCallback(getGAService().getNewAddressBitmap(curSubaccount),
                            onAddress, getGAService().getExecutor());
     }

     private void stopNewAddressAnimation(final View v) {
        final FontAwesomeTextView newAddressIcon = UI.find(v, R.id.receiveNewAddressIcon);
        newAddressIcon.clearAnimation();
        newAddressIcon.setText(Html.fromHtml("&#xf067;"));
        final TextView copyIcon = UI.find(v, R.id.receiveCopyIcon);
        final TextView copyText = UI.find(v, R.id.receiveCopyText);
        UI.show(copyIcon, copyText);
    }

    private void startNewAddressAnimation(final View v) {
        if (getActivity() == null)
            return;

        final FontAwesomeTextView newAddressIcon = UI.find(v, R.id.receiveNewAddressIcon);
        newAddressIcon.setText(Html.fromHtml("&#xf021;"));
        newAddressIcon.setAwesomeTypeface();
        newAddressIcon.setTextSize(TypedValue.COMPLEX_UNIT_DIP, 34);

        final TextView receiveAddress = UI.find(v, R.id.receiveAddressText);
        final TextView copyIcon = UI.find(v, R.id.receiveCopyIcon);
        final TextView copyText = UI.find(v, R.id.receiveCopyText);
        final ImageView imageView = UI.find(v, R.id.receiveQrImageView);
        UI.hide(copyIcon, copyText);
        receiveAddress.setText("");
        imageView.setImageBitmap(null);
    }

    @Override
    public void tagDiscovered(final Tag t) {
    	Log.d("NFC", "Tag discovered " + t);
    }

    @Override
    public void onCreate(final Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setHasOptionsMenu(true);
    }

    @Override
    public void onCreateOptionsMenu (final Menu menu, final MenuInflater inflater) {
        super.onCreateOptionsMenu(menu, inflater);
        inflater.inflate(R.menu.receive, menu);
    }

    @Override
    protected void onSubaccountChanged(final int input) {
        curSubaccount = input;
        if (mView != null)
            startNewAddressAnimation(mView);

        if (!setting_qrcode)
            getNewAddress(null);
    }

    @Override
    public boolean onOptionsItemSelected(final MenuItem item) {
        // Handle action bar item clicks here. The action bar will
        // automatically handle clicks on the Home/Up button, so long
        // as you specify a parent activity in AndroidManifest.xml.
        final int id = item.getItemId();
        if (id == R.id.action_share) {
            if (address != null && !address.getData().isEmpty()) {
                //SHARE intent
                final Intent sendIntent = new Intent();
                sendIntent.setAction(Intent.ACTION_SEND);
                sendIntent.putExtra(Intent.EXTRA_TEXT, BitcoinURI.convertToBitcoinURI(Address.fromBase58(Network.NETWORK, address.getData()), null, null, null));
                sendIntent.setType("text/plain");
                startActivity(sendIntent);
            }
            return true;
        }

        return super.onOptionsItemSelected(item);
    }
}
