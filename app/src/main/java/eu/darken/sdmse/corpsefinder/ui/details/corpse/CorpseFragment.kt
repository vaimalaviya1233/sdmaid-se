package eu.darken.sdmse.corpsefinder.ui.details.corpse

import android.os.Bundle
import android.view.View
import androidx.core.view.isInvisible
import androidx.fragment.app.viewModels
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import dagger.hilt.android.AndroidEntryPoint
import eu.darken.sdmse.R
import eu.darken.sdmse.common.lists.ViewHolderBasedDivider
import eu.darken.sdmse.common.lists.differ.update
import eu.darken.sdmse.common.lists.setupDefaults
import eu.darken.sdmse.common.uix.Fragment3
import eu.darken.sdmse.common.viewbinding.viewBinding
import eu.darken.sdmse.corpsefinder.ui.details.corpse.elements.CorpseElementHeaderVH
import eu.darken.sdmse.databinding.CorpsefinderCorpseFragmentBinding

@AndroidEntryPoint
class CorpseFragment : Fragment3(R.layout.corpsefinder_corpse_fragment) {

    override val vm: CorpseFragmentVM by viewModels()
    override val ui: CorpsefinderCorpseFragmentBinding by viewBinding()

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        val adapter = CorpseElementsAdapter()
        ui.list.apply {
            setupDefaults(adapter, dividers = false)
            val divDec = ViewHolderBasedDivider(requireContext()) { _, cur, _ ->
                cur !is CorpseElementHeaderVH
            }
            addItemDecoration(divDec)
        }

        vm.state.observe2(ui) { state ->
            adapter.update(state.elements)

            list.isInvisible = state.progress != null
            loadingOverlay.setProgress(state.progress)
        }

        vm.events.observe2(ui) {
            when (it) {
                is CorpseEvents.ConfirmDeletion -> MaterialAlertDialogBuilder(requireContext()).apply {
                    setTitle(R.string.general_delete_confirmation_title)
                    setMessage(
                        getString(
                            R.string.general_delete_confirmation_message_x,
                            it.corpse.path.userReadableName.get(context)
                        )
                    )
                    setPositiveButton(R.string.general_delete_action) { _, _ -> vm.doDelete(it.corpse) }
                    setNegativeButton(R.string.general_cancel_action) { _, _ -> }
                }.show()
            }
        }

        super.onViewCreated(view, savedInstanceState)
    }
}
