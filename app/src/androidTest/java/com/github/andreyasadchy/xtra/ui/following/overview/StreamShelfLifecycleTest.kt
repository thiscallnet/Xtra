package com.github.andreyasadchy.xtra.ui.following.overview

import android.content.Intent
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import android.widget.ImageView
import androidx.fragment.app.Fragment
import androidx.fragment.app.commitNow
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.github.andreyasadchy.xtra.R
import com.github.andreyasadchy.xtra.model.ui.Stream
import com.github.andreyasadchy.xtra.repository.streamfeed.StreamThumbnailRefreshSignal
import com.github.andreyasadchy.xtra.ui.common.StreamThumbnailChangedPayload
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class StreamShelfLifecycleTest {

    @Test
    fun normalShelfResumesImagesForAStillBoundHolderAfterSchedulerDetach() {
        withShelf(StreamShelfType.NORMAL, streamId = "normal-lifecycle") { shelf ->
            val holder = awaitFirstHolder(shelf.recyclerView)
            val thumbnail = holder.itemView.findViewById<ImageView>(R.id.thumbnail)
            val initialRequestKey = awaitSuccessfulRequest(thumbnail, generation = 0L)

            StreamThumbnailRefreshSignal.requestForceRefresh()
            InstrumentationRegistry.getInstrumentation().runOnMainSync {
                thumbnail.setImageDrawable(null)
                shelf.resumeImageWork(holder)
            }

            val resumedRequestKey = awaitSuccessfulRequest(
                thumbnail,
                generation = 0L,
                differentFrom = initialRequestKey,
            )
            assertNotEquals(initialRequestKey, resumedRequestKey)
        }
    }

    @Test
    fun featuredShelfResumesImagesForAStillBoundHolderAfterSchedulerDetach() {
        withShelf(StreamShelfType.FEATURED, streamId = "featured-lifecycle") { shelf ->
            val holder = awaitFirstHolder(shelf.recyclerView)
            val thumbnail = holder.itemView.findViewById<ImageView>(R.id.thumbnail)
            val initialRequestKey = awaitSuccessfulRequest(thumbnail, generation = 0L)

            StreamThumbnailRefreshSignal.requestForceRefresh()
            InstrumentationRegistry.getInstrumentation().runOnMainSync {
                thumbnail.setImageDrawable(null)
                shelf.resumeImageWork(holder)
            }

            val resumedRequestKey = awaitSuccessfulRequest(
                thumbnail,
                generation = 0L,
                differentFrom = initialRequestKey,
            )
            assertNotEquals(initialRequestKey, resumedRequestKey)
        }
    }

    @Test
    fun thumbnailPayloadGenerationSurvivesSchedulerDetachAndReattach() {
        withShelf(StreamShelfType.NORMAL, streamId = "generation-lifecycle") { shelf ->
            val holder = awaitFirstHolder(shelf.recyclerView)
            val thumbnail = holder.itemView.findViewById<ImageView>(R.id.thumbnail)
            awaitSuccessfulRequest(thumbnail, generation = 0L)

            StreamThumbnailRefreshSignal.requestForceRefresh()
            shelf.updateWithThumbnailPayload(
                holder,
                testStream(shelf.context, "generation-lifecycle", generation = 1L),
            )

            val updatedRequestKey = awaitSuccessfulRequest(thumbnail, generation = 1L)
            assertTrue(
                "A stale generation was restored after the payload rebind",
                !updatedRequestKey.contains("thumbnailGeneration=0"),
            )
        }
    }

    @Test
    fun recycledHolderDoesNotResumeTheFormerStream() {
        withShelf(StreamShelfType.NORMAL, streamId = "recycled-lifecycle") { shelf ->
            val holder = awaitFirstHolder(shelf.recyclerView)
            val thumbnail = holder.itemView.findViewById<ImageView>(R.id.thumbnail)
            awaitSuccessfulRequest(thumbnail, generation = 0L)

            InstrumentationRegistry.getInstrumentation().runOnMainSync {
                thumbnail.setImageDrawable(null)
                shelf.recycle(holder)
                shelf.attach(holder)
            }

            Thread.sleep(250L)
            assertTrue("A recycled holder resumed stale image work", thumbnail.drawable == null)
        }
    }

    private fun withShelf(
        type: StreamShelfType,
        streamId: String,
        block: (ShelfHarness) -> Unit,
    ) {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val scenario = ActivityScenario.launch<StreamShelfLifecycleTestActivity>(
            Intent(context, StreamShelfLifecycleTestActivity::class.java),
        )
        try {
            lateinit var recyclerView: RecyclerView
            lateinit var resumeImageWork: (RecyclerView.ViewHolder) -> Unit
            lateinit var recycle: (RecyclerView.ViewHolder) -> Unit
            lateinit var attach: (RecyclerView.ViewHolder) -> Unit
            lateinit var updateWithThumbnailPayload: (RecyclerView.ViewHolder, Stream) -> Unit
            scenario.onActivity { activity ->
                val host = TestHostFragment()
                activity.supportFragmentManager.commitNow {
                    add(activity.root.id, host)
                }
                recyclerView = RecyclerView(activity).apply {
                    layoutManager = LinearLayoutManager(activity, RecyclerView.HORIZONTAL, false)
                    layoutParams = ViewGroup.LayoutParams(
                        ViewGroup.LayoutParams.MATCH_PARENT,
                        ViewGroup.LayoutParams.WRAP_CONTENT,
                    )
                }
                (host.requireView() as FrameLayout).addView(recyclerView)
                when (type) {
                    StreamShelfType.NORMAL -> StreamShelfAdapter(host, {}, {}).also { adapter ->
                        recyclerView.adapter = adapter
                        adapter.submitList(listOf(testStream(context, streamId)))
                        resumeImageWork = { holder ->
                            adapter.onDetachedFromRecyclerView(recyclerView)
                            adapter.onAttachedToRecyclerView(recyclerView)
                            adapter.onViewAttachedToWindow(holder as StreamShelfAdapter.ViewHolder)
                        }
                        recycle = { holder -> adapter.onViewRecycled(holder as StreamShelfAdapter.ViewHolder) }
                        attach = { holder -> adapter.onViewAttachedToWindow(holder as StreamShelfAdapter.ViewHolder) }
                        updateWithThumbnailPayload = { holder, stream ->
                            adapter.submitList(listOf(stream)) {
                                adapter.onBindViewHolder(
                                    holder as StreamShelfAdapter.ViewHolder,
                                    0,
                                    mutableListOf(StreamThumbnailChangedPayload),
                                )
                                adapter.onDetachedFromRecyclerView(recyclerView)
                                adapter.onAttachedToRecyclerView(recyclerView)
                                adapter.onViewAttachedToWindow(holder)
                            }
                        }
                    }
                    StreamShelfType.FEATURED -> FeaturedStreamShelfAdapter(host, {}, {}).also { adapter ->
                        recyclerView.adapter = adapter
                        adapter.submitList(listOf(testStream(context, streamId)))
                        resumeImageWork = { holder ->
                            adapter.onDetachedFromRecyclerView(recyclerView)
                            adapter.onAttachedToRecyclerView(recyclerView)
                            adapter.onViewAttachedToWindow(holder as FeaturedStreamShelfAdapter.ViewHolder)
                        }
                        recycle = { holder -> adapter.onViewRecycled(holder as FeaturedStreamShelfAdapter.ViewHolder) }
                        attach = { holder -> adapter.onViewAttachedToWindow(holder as FeaturedStreamShelfAdapter.ViewHolder) }
                        updateWithThumbnailPayload = { holder, stream ->
                            adapter.submitList(listOf(stream)) {
                                adapter.onBindViewHolder(
                                    holder as FeaturedStreamShelfAdapter.ViewHolder,
                                    0,
                                    mutableListOf(StreamThumbnailChangedPayload),
                                )
                                adapter.onDetachedFromRecyclerView(recyclerView)
                                adapter.onAttachedToRecyclerView(recyclerView)
                                adapter.onViewAttachedToWindow(holder)
                            }
                        }
                    }
                }
            }
            block(
                ShelfHarness(
                    recyclerView = recyclerView,
                    resumeImageWork = resumeImageWork,
                    recycle = recycle,
                    attach = attach,
                    updateWithThumbnailPayload = updateWithThumbnailPayload,
                    context = context,
                ),
            )
        } finally {
            scenario.close()
        }
    }

    private fun testStream(
        context: android.content.Context,
        id: String,
        generation: Long = 0L,
    ): Stream = Stream(
        id = id,
        channelId = "lifecycle-channel",
        channelLogin = "lifecycle_channel",
        channelName = "Lifecycle channel",
        thumbnailURL = "android.resource://${context.packageName}/${R.drawable.bg_thumbnail_placeholder}",
        title = "Lifecycle thumbnail",
        createdAt = "2026-09-12T00:00:00Z",
        viewerCount = 1,
        thumbnailGeneration = generation,
    )

    private fun awaitFirstHolder(recyclerView: RecyclerView): RecyclerView.ViewHolder {
        awaitCondition { recyclerView.childCount > 0 }
        return recyclerView.getChildViewHolder(recyclerView.getChildAt(0))
    }

    private fun awaitSuccessfulRequest(
        view: ImageView,
        generation: Long,
        differentFrom: String? = null,
    ): String {
        var requestKey = ""
        awaitCondition {
            requestKey = view.getTag(R.id.stream_thumbnail_successful_fresh_key)
                ?.toString()
                .orEmpty()
            requestKey.contains("thumbnailGeneration=$generation") &&
                (differentFrom == null || requestKey != differentFrom)
        }
        assertNotNull(view.drawable)
        return requestKey
    }

    private fun awaitCondition(condition: () -> Boolean) {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val deadline = System.currentTimeMillis() + 5_000L
        while (System.currentTimeMillis() < deadline) {
            instrumentation.waitForIdleSync()
            if (condition()) return
            Thread.sleep(25L)
        }
        assertTrue("Condition was not reached before timeout", condition())
    }

    private enum class StreamShelfType { NORMAL, FEATURED }

    private data class ShelfHarness(
        val recyclerView: RecyclerView,
        val resumeImageWork: (RecyclerView.ViewHolder) -> Unit,
        val recycle: (RecyclerView.ViewHolder) -> Unit,
        val attach: (RecyclerView.ViewHolder) -> Unit,
        val updateWithThumbnailPayload: (RecyclerView.ViewHolder, Stream) -> Unit,
        val context: android.content.Context,
    )

    class TestHostFragment : Fragment() {
        override fun onCreateView(
            inflater: LayoutInflater,
            container: ViewGroup?,
            savedInstanceState: android.os.Bundle?,
        ): View = FrameLayout(requireContext())
    }
}
