package me.devsaki.hentoid.fragments.library

import android.content.DialogInterface
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.EditText
import androidx.fragment.app.DialogFragment
import androidx.fragment.app.Fragment
import androidx.recyclerview.widget.ItemTouchHelper
import androidx.recyclerview.widget.RecyclerView
import com.mikepenz.fastadapter.FastAdapter
import com.mikepenz.fastadapter.adapters.ItemAdapter
import com.mikepenz.fastadapter.drag.ItemTouchCallback
import com.mikepenz.fastadapter.drag.SimpleDragCallback
import com.mikepenz.fastadapter.utils.DragDropUtil.onMove
import me.devsaki.hentoid.database.CollectionDAO
import me.devsaki.hentoid.database.ObjectBoxDAO
import me.devsaki.hentoid.database.domains.Content
import me.devsaki.hentoid.databinding.DialogLibraryMergeBinding
import me.devsaki.hentoid.enums.StatusContent
import me.devsaki.hentoid.util.Preferences
import me.devsaki.hentoid.viewholders.IDraggableViewHolder
import me.devsaki.hentoid.viewholders.TextItem

class MergeDialogFragment : DialogFragment(), ItemTouchCallback {

    companion object {
        private const val KEY_CONTENTS = "contents"
        private const val KEY_DELETE_DEFAULT = "delete_default"

        operator fun invoke(
            parent: Fragment,
            contentList: List<Content>,
            deleteDefault: Boolean
        ) {
            val fragment = MergeDialogFragment()
            val args = Bundle()
            args.putLongArray(
                KEY_CONTENTS,
                contentList.map { obj: Content -> obj.id }.toLongArray()
            )
            args.putBoolean(KEY_DELETE_DEFAULT, deleteDefault)
            fragment.arguments = args
            fragment.show(parent.childFragmentManager, null)
        }
    }

    // === UI
    private var binding: DialogLibraryMergeBinding? = null
    private var newTitleTxt: EditText? = null

    private val itemAdapter = ItemAdapter<TextItem<Content>>()
    private val fastAdapter: FastAdapter<TextItem<Content>> = FastAdapter.with(itemAdapter)
    private var touchHelper: ItemTouchHelper? = null

    // === VARIABLES
    private var parent: Parent? = null
    private lateinit var contentIds: LongArray
    private var deleteDefault = false
    private var initialTitle = ""


    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val contentIdsVal = requireArguments().getLongArray(KEY_CONTENTS)
        require(contentIdsVal != null && contentIdsVal.isNotEmpty()) { "No content IDs" }
        contentIds = contentIdsVal
        deleteDefault = requireArguments().getBoolean(KEY_DELETE_DEFAULT, false)
        parent = parentFragment as Parent?
    }

    override fun onDestroy() {
        parent = null
        super.onDestroy()
    }

    override fun onCancel(dialog: DialogInterface) {
        parent?.leaveSelectionMode()
        super.onCancel(dialog)
    }

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedState: Bundle?
    ): View {
        binding = DialogLibraryMergeBinding.inflate(inflater, container, false)
        return binding!!.root
    }

    override fun onDestroyView() {
        super.onDestroyView()
        binding = null
    }

    override fun onViewCreated(rootView: View, savedInstanceState: Bundle?) {
        super.onViewCreated(rootView, savedInstanceState)
        val contentList = loadContentList()
        if (contentList.isEmpty()) return
        val isExternal = contentList[0].status == StatusContent.EXTERNAL
        itemAdapter.set(contentList.map { s: Content ->
            TextItem(
                s.title,
                s,
                true,
                false,
                false,
                touchHelper
            )
        })

        // Activate drag & drop
        val dragCallback = SimpleDragCallback(this)
        dragCallback.notifyAllDrops = true
        touchHelper = ItemTouchHelper(dragCallback)
        binding?.apply {
            touchHelper!!.attachToRecyclerView(list)
            fastAdapter.addEventHook(
                TextItem.DragHandlerTouchEvent { position: Int ->
                    val vh = list.findViewHolderForAdapterPosition(position)
                    if (vh != null) touchHelper!!.startDrag(vh)
                }
            )
            list.adapter = fastAdapter
            initialTitle = contentList[0].title
            newTitleTxt = titleNew.editText
            if (newTitleTxt != null) newTitleTxt!!.setText(initialTitle)
            if (isExternal) {
                mergeDeleteSwitch.isEnabled = Preferences.isDeleteExternalLibrary()
                mergeDeleteSwitch.isChecked = Preferences.isDeleteExternalLibrary() && deleteDefault
            } else {
                mergeDeleteSwitch.isEnabled = true
                mergeDeleteSwitch.isChecked = deleteDefault
            }
            actionButton.setOnClickListener { onActionClick() }
        }
    }

    private fun loadContentList(): List<Content> {
        val result: List<Content>
        val dao: CollectionDAO = ObjectBoxDAO(requireContext())
        result = try {
            dao.selectContent(contentIds)
        } finally {
            dao.cleanup()
        }
        return result
    }

    private fun onActionClick() {
        val contents = itemAdapter.adapterItems.mapNotNull { ti: TextItem<Content> -> ti.getTag() }
        val newTitleStr = if (null == newTitleTxt) "" else newTitleTxt!!.text.toString()
        binding?.let {
            parent?.mergeContents(contents, newTitleStr, it.mergeDeleteSwitch.isChecked)
        }
        dismissAllowingStateLoss()
    }


    // FastAdapter hooks

    // FastAdapter hooks
    override fun itemTouchDropped(oldPosition: Int, newPosition: Int) {
        // Update visuals
        binding?.let {
            val vh = it.list.findViewHolderForAdapterPosition(newPosition)
            if (vh is IDraggableViewHolder) {
                (vh as IDraggableViewHolder).onDropped()
            }
        }
        // Update new title if unedited
        if (0 == newPosition && newTitleTxt!!.text.toString() == initialTitle) {
            initialTitle = itemAdapter.getAdapterItem(0).text
            newTitleTxt!!.setText(initialTitle)
        }
    }

    override fun itemTouchOnMove(oldPosition: Int, newPosition: Int): Boolean {
        // Update visuals
        onMove(itemAdapter, oldPosition, newPosition) // change position
        return true
    }

    override fun itemTouchStartDrag(viewHolder: RecyclerView.ViewHolder) {
        // Update visuals
        if (viewHolder is IDraggableViewHolder) {
            (viewHolder as IDraggableViewHolder).onDragged()
        }
    }

    override fun itemTouchStopDrag(viewHolder: RecyclerView.ViewHolder) {
        // Nothing
    }

    interface Parent {
        fun mergeContents(
            contentList: List<Content>,
            newTitle: String,
            deleteAfterMerging: Boolean
        )

        fun leaveSelectionMode()
    }
}