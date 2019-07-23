package com.mixed.apm

import android.app.Activity
import android.content.*
import android.os.Bundle
import android.support.v7.app.AlertDialog
import android.support.v7.app.AppCompatActivity
import android.util.Log
import android.util.TypedValue
import android.view.View
import android.widget.TextView
import android.widget.Toast
import kotlinx.android.synthetic.main.activity_default_error.*

class DefaultErrorActivity : AppCompatActivity(), View.OnClickListener {

    val instance by lazy { this }


    var restartActivity:Class<out Activity>?=null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_default_error)
        btn_error_exit.setOnClickListener(this)
        btn_error_detail.setOnClickListener(this)
        iv_error_image.setImageResource(R.drawable.customactivityoncrash_error_image)
        restartActivity = CustomActivityOnCrash.getRestartActivityFromIntent(intent)
        if(restartActivity!=null){
            btn_error_exit.setText("重启应用")
        }

    }

    override fun onClick(p0: View?) {
        when (p0?.id) {
            R.id.btn_error_exit -> {
                if (restartActivity== null){
                    //关闭应用
                    CustomActivityOnCrash.closeApplication(this)
                }else{
                    CustomActivityOnCrash.restartAppWithIntent(this,Intent(this,restartActivity))
                }
            }
            R.id.btn_error_detail -> {
                 AlertDialog.Builder(this)
                    .setTitle("Error Details")
                    .setMessage(CustomActivityOnCrash.getErrorDetailsFromIntent(this, intent))
                    .setPositiveButton("Close", null)
                    .setNegativeButton("Copy to clipboard", object : DialogInterface.OnClickListener {
                        override fun onClick(p0: DialogInterface?, p1: Int) {
                            copyErrorToClipboard()
                            Toast.makeText(instance, "Copy to clipboard", Toast.LENGTH_SHORT)
                        }

                    }).show()
            }

        }
    }

    private fun copyErrorToClipboard() {
        val errorInfo=CustomActivityOnCrash.getErrorDetailsFromIntent(this,intent)
        val cm=getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
        val clipData=ClipData.newPlainText("Error Information",errorInfo)
        cm.setPrimaryClip(clipData)
    }

}