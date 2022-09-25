package ani.saikou.anime

import android.graphics.Color.TRANSPARENT
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.fragment.app.activityViewModels
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import ani.saikou.BottomSheetDialogFragment
import ani.saikou.R
import ani.saikou.databinding.BottomSheetSubtitlesBinding
import ani.saikou.databinding.ItemSubtitleTextBinding
import ani.saikou.media.MediaDetailsViewModel
import ani.saikou.parsers.Subtitle

class SubtitleDialogFragment : BottomSheetDialogFragment() {
    private var _binding: BottomSheetSubtitlesBinding? = null
    private val binding get() = _binding!!
    val model: MediaDetailsViewModel by activityViewModels()
    private lateinit var episode: Episode

    override fun onCreateView(inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?): View {
        _binding = BottomSheetSubtitlesBinding.inflate(inflater, container, false)
        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        model.getMedia().observe(viewLifecycleOwner) { media ->
            episode = media?.anime?.episodes?.get(media.anime.selectedEpisode) ?: return@observe
            val currentExtractor = episode.extractors?.find { it.server.name == episode.selectedExtractor } ?: return@observe
            binding.subtitlesRecycler.layoutManager = LinearLayoutManager(requireContext())
            binding.subtitlesRecycler.adapter = SubtitleAdapter(currentExtractor.subtitles)
        }
    }

    inner class SubtitleAdapter(val subtitles: List<Subtitle>) : RecyclerView.Adapter<SubtitleAdapter.StreamViewHolder>() {
        inner class StreamViewHolder(val binding: ItemSubtitleTextBinding) : RecyclerView.ViewHolder(binding.root)

        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): StreamViewHolder =
            StreamViewHolder(ItemSubtitleTextBinding.inflate(LayoutInflater.from(parent.context), parent, false))

        override fun onBindViewHolder(holder: StreamViewHolder, position: Int) {
            val binding = holder.binding
            if (position == 0) {
                binding.subtitleTitle.setText(R.string.none)
                if(episode.selectedSubtitle!=null) binding.root.setCardBackgroundColor(TRANSPARENT)
                binding.root.setOnClickListener {
                    episode.selectedSubtitle = null
                    model.setEpisode(episode, "Subtitle")
                    dismiss()
                }
            } else {
                binding.subtitleTitle.text = subtitles[position - 1].language
                if(episode.selectedSubtitle != position-1)
                    binding.root.setCardBackgroundColor(TRANSPARENT)
                binding.root.setOnClickListener {
                    episode.selectedSubtitle = position - 1
                    model.setEpisode(episode, "Subtitle")
                    dismiss()
                }
            }
        }

        override fun getItemCount(): Int = subtitles.size + 1
    }

    override fun onDestroy() {
        _binding = null
        super.onDestroy()
    }
}