package me.devsaki.hentoid.views

import android.content.Context
import android.content.DialogInterface
import android.util.AttributeSet
import android.view.LayoutInflater
import androidx.appcompat.app.AlertDialog
import androidx.constraintlayout.widget.ConstraintLayout
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import me.devsaki.hentoid.R
import me.devsaki.hentoid.databinding.WidgetListPickerBinding


class ListPickerView : ConstraintLayout {
    private val binding = WidgetListPickerBinding.inflate(LayoutInflater.from(context), this, true)

    private var title = ""
    private var entries: Array<CharSequence> = emptyArray()
    private var entriesId = -1
    private var currentEntry = ""

    private var onIndexChangeListener: ((Int) -> Unit)? = null

    fun setIndex(value: Int) {
        selectIndex(value, false)
    }

    fun getIndex(): Int {
        return entries.indexOf(currentEntry)
    }

    constructor(context: Context) : super(context)

    constructor(context: Context, attrs: AttributeSet?) : super(context, attrs) {
        initialize(context, attrs)
    }

    constructor(context: Context, attrs: AttributeSet?, defStyle: Int)
            : super(context, attrs, defStyle) {
        initialize(context, attrs)
    }

    private fun initialize(context: Context, attrs: AttributeSet?) {
        val arr = context.obtainStyledAttributes(attrs, R.styleable.ListPickerView)
        try {
            title = arr.getString(R.styleable.ListPickerView_title) ?: ""
            entriesId = arr.getResourceId(R.styleable.ListPickerView_entries, -1)
            entries = arr.getTextArray(R.styleable.ListPickerView_entries) ?: emptyArray()
            currentEntry = arr.getString(R.styleable.ListPickerView_currentEntry) ?: ""
        } finally {
            arr.recycle()
        }

        binding.let {
            it.title.text = title
            it.description.text = currentEntry
            it.root.setOnClickListener { onClick() }
        }
    }

    fun setOnIndexChangeListener(listener: (Int) -> Unit) {
        onIndexChangeListener = listener
    }

    private fun onClick() {
        val materialDialog: AlertDialog = MaterialAlertDialogBuilder(context)
            .setSingleChoiceItems(
                entriesId,
                getIndex(),
                this::onSelect
            )
            .setCancelable(true)
            .create()

        materialDialog.show()
    }

    private fun onSelect(dialog: DialogInterface, selectedIndex: Int) {
        selectIndex(selectedIndex)
        dialog.dismiss()
    }

    private fun selectIndex(selectedIndex: Int, triggerListener: Boolean = true) {
        currentEntry = entries[selectedIndex].toString()
        binding.description.text = currentEntry
        if (triggerListener) onIndexChangeListener?.invoke(selectedIndex)
    }
}