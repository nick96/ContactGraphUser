/*
 * Copyright (C) 2014 The Android Open Source Project
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

package com.example.android.bluetoothchat

import android.app.Activity
import android.bluetooth.BluetoothAdapter
import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.os.Handler
import android.os.Message
import android.view.*
import android.widget.ArrayAdapter
import android.widget.Button
import android.widget.Toast
import androidx.fragment.app.Fragment
import com.example.android.common.logger.Log
import com.google.android.material.snackbar.Snackbar
import java.util.*

/**
 * This fragment controls Bluetooth to communicate with other devices.
 */
class BluetoothChatFragment : Fragment() {

    private var mSendButton: Button? = null

    /**
     * Name of the connected device
     */
    private var mConnectedDeviceName: String? = null

    /**
     * Array adapter for the conversation thread
     */
    private var mConversationArrayAdapter: ArrayAdapter<String>? = null

    /**
     * String buffer for outgoing messages
     */
    private var mOutStringBuffer: StringBuffer? = null

    /**
     * Local Bluetooth adapter
     */
    private var mBluetoothAdapter: BluetoothAdapter? = null

    /**
     * Member object for the chat services
     */
    private var mChatService: BluetoothChatService? = null

    private var mKey: String? = null

    /**
     * The Handler that gets information back from the BluetoothChatService
     */
    private val mHandler = object : Handler() {
        override fun handleMessage(msg: Message) {
            val activity = activity
            when (msg.what) {
                Constants.MESSAGE_STATE_CHANGE -> when (msg.arg1) {
                    BluetoothChatService.STATE_CONNECTED -> {
                        setStatus(getString(R.string.title_connected_to, mConnectedDeviceName))
                        mConversationArrayAdapter!!.clear()
                    }
                    BluetoothChatService.STATE_CONNECTING -> setStatus(R.string.title_connecting)
                    BluetoothChatService.STATE_LISTEN, BluetoothChatService.STATE_NONE -> setStatus(R.string.title_not_connected)
                }
                Constants.MESSAGE_WRITE -> {
                    val writeBuf = msg.obj as ByteArray
                    // construct a string from the buffer
                    val writeMessage = writeBuf.toString()
                    mConversationArrayAdapter!!.add("Me:  $writeMessage")
                }
                Constants.MESSAGE_READ -> {
                    val readBuf = msg.obj as ByteArray
                    // construct a string from the valid bytes in the buffer
                    val readMessage = String(readBuf, 0, msg.arg1)
                    mConversationArrayAdapter!!.add("$mConnectedDeviceName:  $readMessage")
                }
                Constants.MESSAGE_DEVICE_NAME -> {
                    // save the connected device's name
                    mConnectedDeviceName = msg.data.getString(Constants.DEVICE_NAME)
                    if (null != activity) {
                        Toast.makeText(activity, "Connected to " + mConnectedDeviceName!!, Toast.LENGTH_SHORT).show()
                    }
                }
                Constants.MESSAGE_TOAST -> if (null != activity) {
                    Toast.makeText(activity, msg.data.getString(Constants.TOAST),
                            Toast.LENGTH_SHORT).show()
                }
            }
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setHasOptionsMenu(true)
        // Get local Bluetooth adapter
        mBluetoothAdapter = BluetoothAdapter.getDefaultAdapter()

        // If the adapter is null, then Bluetooth is not supported
        val activity = activity
        if (mBluetoothAdapter == null && activity != null) {
            Toast.makeText(activity, "Bluetooth is not available", Toast.LENGTH_LONG).show()
            activity.finish()
        }
    }

    override fun onStart() {
        super.onStart()
        if (mBluetoothAdapter == null) {
            return
        }
        // If BT is not on, request that it be enabled.
        // setupChat() will then be called during onActivityResult
        if (!mBluetoothAdapter!!.isEnabled) {
            val enableIntent = Intent(BluetoothAdapter.ACTION_REQUEST_ENABLE)
            startActivityForResult(enableIntent, REQUEST_ENABLE_BT)
            // Otherwise, setup the chat session
        } else if (mChatService == null) {
            setupChat()
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        if (mChatService != null) {
            mChatService!!.stop()
        }
    }

    override fun onResume() {
        super.onResume()

        // Performing this check in onResume() covers the case in which BT was
        // not enabled during onStart(), so we were paused to enable it...
        // onResume() will be called when ACTION_REQUEST_ENABLE activity returns.
        if (mChatService != null) {
            // Only if the state is STATE_NONE, do we know that we haven't started already
            if (mChatService!!.threadState == BluetoothChatService.STATE_NONE) {
                // Start the Bluetooth chat services
                mChatService!!.start()
            }
        }
    }

    override fun onCreateView(inflater: LayoutInflater, container: ViewGroup?,
                              savedInstanceState: Bundle?): View? {
        return inflater.inflate(R.layout.fragment_bluetooth_chat, container, false)
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        mSendButton = view.findViewById(R.id.button_send)
        val resetButton = view.findViewById<Button>(R.id.button_reset)
        resetButton.setOnClickListener {
            val prefs = activity!!.getPreferences(Context.MODE_PRIVATE)
            val key = UUID.randomUUID().toString()
            with (prefs.edit()) {
                putString("key", key)
                commit()
            }
            mKey = key
            Toast.makeText(context, "Reset key to $mKey", Toast.LENGTH_SHORT).show()
        }

        val prefs = activity!!.getPreferences(Context.MODE_PRIVATE)
        mKey = prefs.getString("key", null)
        if (mKey == null) {
            mKey = UUID.randomUUID().toString()
            with(prefs.edit()) {
                putString("key", mKey)
                commit()
            }
        }
    }

    /**
     * Set up the UI and background operations for chat.
     */
    private fun setupChat() {
        Log.d(TAG, "setupChat()")

        // Initialize the array adapter for the conversation thread
        val activity = activity ?: return
        mConversationArrayAdapter = ArrayAdapter(activity, R.layout.message)

//        mConversationView!!.adapter = mConversationArrayAdapter

        // Initialize the compose field with a listener for the return key
//        mOutEditText!!.setOnEditorActionListener(mWriteListener)

        // Initialize the send button with a listener that for click events
        mSendButton!!.setOnClickListener {
            // Send a message using content of the edit text widget
            val view = view
            if (null != view) {
//                val textView = view.findViewById<TextView>(R.id.edit_text_out)
//                val message = textView.text.toString()
                if (mKey != null) {
                    Log.d("send", "Sending message '$mKey'")
                    sendMessage(mKey!!)
                } else {
                    Snackbar.make(view, "There was a problem sending the key", Snackbar.LENGTH_SHORT).show()
                }
            }
        }

        // Initialize the BluetoothChatService to perform bluetooth connections
        mChatService = BluetoothChatService(mHandler)

        // Initialize the buffer for outgoing messages
        mOutStringBuffer = StringBuffer()
    }

    /**
     * Makes this device discoverable for 300 seconds (5 minutes).
     */
    private fun ensureDiscoverable() {
        if (mBluetoothAdapter!!.scanMode != BluetoothAdapter.SCAN_MODE_CONNECTABLE_DISCOVERABLE) {
            val discoverableIntent = Intent(BluetoothAdapter.ACTION_REQUEST_DISCOVERABLE)
            discoverableIntent.putExtra(BluetoothAdapter.EXTRA_DISCOVERABLE_DURATION, 300)
            startActivity(discoverableIntent)
        }
    }

    /**
     * Sends a message.
     *
     * @param message A string of text to send.
     */
    private fun sendMessage(message: String) {
        // Check that we're actually connected before trying anything
        if (mChatService!!.threadState != BluetoothChatService.STATE_CONNECTED) {
            Toast.makeText(activity, R.string.not_connected, Toast.LENGTH_SHORT).show()
            return
        }

        // Check that there's actually something to send
        if (message.length > 0) {
            // Get the message bytes and tell the BluetoothChatService to write
            val send = message.toByteArray()
            mChatService!!.write(send)

            // Reset out string buffer to zero and clear the edit text field
            mOutStringBuffer!!.setLength(0)
//            mOutEditText!!.setText(mOutStringBuffer)
        }
    }

    /**
     * Updates the status on the action bar.
     *
     * @param resId a string resource ID
     */
    private fun setStatus(resId: Int) {
        val activity = activity ?: return
        val actionBar = activity.actionBar ?: return
        actionBar.setSubtitle(resId)
    }

    /**
     * Updates the status on the action bar.
     *
     * @param subTitle status
     */
    private fun setStatus(subTitle: CharSequence) {
        val activity = activity ?: return
        val actionBar = activity.actionBar ?: return
        actionBar.subtitle = subTitle
    }

    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        when (requestCode) {
            REQUEST_CONNECT_DEVICE_SECURE ->
                // When DeviceListActivity returns with a device to connect
                if (resultCode == Activity.RESULT_OK) {
                    connectDevice(data!!, true)
                }
            REQUEST_CONNECT_DEVICE_INSECURE ->
                // When DeviceListActivity returns with a device to connect
                if (resultCode == Activity.RESULT_OK) {
                    connectDevice(data!!, false)
                }
            REQUEST_ENABLE_BT ->
                // When the request to enable Bluetooth returns
                if (resultCode == Activity.RESULT_OK) {
                    // Bluetooth is now enabled, so set up a chat session
                    setupChat()
                } else {
                    // User did not enable Bluetooth or an error occurred
                    Log.d(TAG, "BT not enabled")
                    val activity = activity
                    if (activity != null) {
                        Toast.makeText(activity, R.string.bt_not_enabled_leaving,
                                Toast.LENGTH_SHORT).show()
                        activity.finish()
                    }
                }
        }
    }

    /**
     * Establish connection with other device
     *
     * @param data   An [Intent] with [DeviceListActivity.EXTRA_DEVICE_ADDRESS] extra.
     * @param secure Socket Security type - Secure (true) , Insecure (false)
     */
    private fun connectDevice(data: Intent, secure: Boolean) {
        // Get the device MAC address
        val extras = data.extras ?: return
        val address = extras.getString(DeviceListActivity.EXTRA_DEVICE_ADDRESS)
        // Get the BluetoothDevice object
        val device = mBluetoothAdapter!!.getRemoteDevice(address)
        // Attempt to connect to the device
        mChatService!!.connect(device, secure)
    }

    override fun onCreateOptionsMenu(menu: Menu, inflater: MenuInflater) {
        inflater.inflate(R.menu.bluetooth_chat, menu)
    }

    override fun onOptionsItemSelected(item: MenuItem): Boolean {
        when (item.itemId) {
            R.id.secure_connect_scan -> {
                // Launch the DeviceListActivity to see devices and do scan
                val serverIntent = Intent(activity, DeviceListActivity::class.java)
                startActivityForResult(serverIntent, REQUEST_CONNECT_DEVICE_SECURE)
                return true
            }
            R.id.insecure_connect_scan -> {
                // Launch the DeviceListActivity to see devices and do scan
                val serverIntent = Intent(activity, DeviceListActivity::class.java)
                startActivityForResult(serverIntent, REQUEST_CONNECT_DEVICE_INSECURE)
                return true
            }
            R.id.discoverable -> {
                // Ensure this device is discoverable by others
                ensureDiscoverable()
                return true
            }
        }
        return false
    }

    companion object {

        private val TAG = "BluetoothChatFragment"

        // Intent request codes
        private val REQUEST_CONNECT_DEVICE_SECURE = 1
        private val REQUEST_CONNECT_DEVICE_INSECURE = 2
        private val REQUEST_ENABLE_BT = 3
    }

}
