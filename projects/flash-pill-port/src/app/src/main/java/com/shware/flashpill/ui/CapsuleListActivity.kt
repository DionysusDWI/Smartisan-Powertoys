package com.shware.flashpill.ui

import android.graphics.Color
import android.graphics.drawable.GradientDrawable
import android.os.Bundle
import android.text.Editable
import android.text.TextWatcher
import android.view.View
import android.widget.LinearLayout
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.LinearLayoutManager
import com.shware.flashpill.data.Capsule
import com.shware.flashpill.data.CapsuleRepository
import com.shware.flashpill.databinding.ActivityCapsuleListBinding
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.launch

@OptIn(ExperimentalCoroutinesApi::class)
class CapsuleListActivity : AppCompatActivity() {

    private lateinit var binding: ActivityCapsuleListBinding
    private val adapter = CapsuleAdapter(
        onClick = { capsule -> showDetail(capsule) },
        onToggleDone = { capsule -> toggleDone(capsule) },
    )

    private val queryFlow = MutableStateFlow("")
    private val tagFlow = MutableStateFlow<String?>(null)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityCapsuleListBinding.inflate(layoutInflater)
        setContentView(binding.root)

        binding.recycler.layoutManager = LinearLayoutManager(this)
        binding.recycler.adapter = adapter

        buildTagFilter()

        binding.etSearch.addTextChangedListener(object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, st: Int, c: Int, a: Int) = Unit
            override fun onTextChanged(s: CharSequence?, st: Int, b: Int, c: Int) = Unit
            override fun afterTextChanged(s: Editable?) {
                queryFlow.value = s?.toString()?.trim().orEmpty()
            }
        })

        val repo = CapsuleRepository.get(this)
        lifecycleScope.launch {
            combine(queryFlow, tagFlow) { query, tag -> query to tag }
                .flatMapLatest { (query, tag) -> repo.observeFiltered(query, tag) }
                .collectLatest { list ->
                    adapter.submitList(list)
                    binding.tvEmpty.visibility =
                        if (list.isEmpty()) View.VISIBLE else View.GONE
                }
        }
    }

    /** 色标筛选行：全部 + 5 色 */
    private fun buildTagFilter() {
        val container: LinearLayout = binding.tagRow
        container.removeAllViews()

        val options = mutableListOf<Pair<String?, Int>>()
        options.add(null to Color.parseColor("#888888"))
        options.addAll(CapsuleDetailDialog.TAG_COLORS)

        val size = dp(28)
        for ((key, color) in options) {
            val dot = View(this).apply {
                layoutParams = LinearLayout.LayoutParams(size, size).apply { marginEnd = dp(10) }
                background = GradientDrawable().apply {
                    shape = GradientDrawable.OVAL
                    setColor(color)
                    if (key == tagFlow.value) {
                        setStroke(dp(3), Color.WHITE)
                    } else {
                        setStroke(0, Color.TRANSPARENT)
                    }
                }
                setOnClickListener {
                    tagFlow.value = if (key == null || tagFlow.value == key) null else key
                    buildTagFilter()
                }
            }
            container.addView(dot)
        }
    }

    private fun showDetail(capsule: Capsule) {
        CapsuleDetailDialog(this, capsule) { }.show()
    }

    private fun toggleDone(capsule: Capsule) {
        lifecycleScope.launch {
            CapsuleRepository.get(this@CapsuleListActivity)
                .update(capsule.copy(done = !capsule.done))
        }
    }

    private fun dp(value: Int): Int = (value * resources.displayMetrics.density).toInt()
}