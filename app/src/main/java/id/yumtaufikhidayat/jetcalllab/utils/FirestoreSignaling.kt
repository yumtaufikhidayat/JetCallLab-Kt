package id.yumtaufikhidayat.jetcalllab.utils

import android.util.Log
import com.google.firebase.firestore.CollectionReference
import com.google.firebase.firestore.DocumentChange
import com.google.firebase.firestore.FirebaseFirestore
import com.google.firebase.firestore.ListenerRegistration
import kotlinx.coroutines.tasks.await
import org.webrtc.IceCandidate
import org.webrtc.SessionDescription

class FirestoreSignaling(
    private val db: FirebaseFirestore = FirebaseFirestore.getInstance()
) {
    fun roomRef(roomId: String) = db
        .collection("rooms")
        .document(roomId)

    fun callerCandidates(roomId: String) = roomRef(roomId).collection("callerCandidates")

    fun calleeCandidates(roomId: String) = roomRef(roomId).collection("calleeCandidates")

    suspend fun publishOffer(roomId: String, sdp: SessionDescription) {
        roomRef(roomId)
            .collection(KEY_OFFER)
            .document(KEY_SDP)
            .set(
                mapOf(
                    KEY_TYPE to sdp.type.canonicalForm(),
                    KEY_SDP to sdp.description
                )
            )
            .await()
    }

    suspend fun publishAnswer(roomId: String, sdp: SessionDescription) {
        roomRef(roomId)
            .collection(KEY_ANSWER)
            .document(KEY_SDP)
            .set(
                mapOf(
                    KEY_TYPE to sdp.type.canonicalForm(),
                    KEY_SDP to sdp.description
                )
            )
            .await()
    }

    fun listenOffer(roomId: String, onOffer: (SessionDescription) -> Unit): ListenerRegistration {
        var consumed = false
        return roomRef(roomId).collection(KEY_OFFER).document(KEY_SDP)
            .addSnapshotListener { snap, exception ->
                if (exception != null) return@addSnapshotListener
                if (consumed) return@addSnapshotListener

                val data = snap?.data ?: return@addSnapshotListener
                val typeStr = data[KEY_TYPE] as? String ?: return@addSnapshotListener
                val sdpStr  = data[KEY_SDP] as? String ?: return@addSnapshotListener
                consumed = true
                onOffer(SessionDescription(SessionDescription.Type.fromCanonicalForm(typeStr), sdpStr))
            }
    }

    fun listenAnswer(roomId: String, onAnswer: (SessionDescription) -> Unit): ListenerRegistration {
        var consumed = false
        return roomRef(roomId).collection(KEY_ANSWER).document(KEY_SDP)
            .addSnapshotListener { snap, exception ->
                if (exception != null) return@addSnapshotListener
                if (consumed) return@addSnapshotListener

                val data = snap?.data ?: return@addSnapshotListener
                val typeStr = data[KEY_TYPE] as? String ?: return@addSnapshotListener
                val sdpStr  = data[KEY_SDP] as? String ?: return@addSnapshotListener
                consumed = true
                onAnswer(SessionDescription(SessionDescription.Type.fromCanonicalForm(typeStr), sdpStr))
            }
    }

    suspend fun addCallerCandidate(roomId: String, candidate: IceCandidate) {
        runCatching {
            callerCandidates(roomId).add(
                mapOf(
                    KEY_CANDIDATE to candidate.sdp,
                    KEY_SDP_MID to (candidate.sdpMid.orEmpty()),
                    KEY_SDP_MLINE_INDEX to candidate.sdpMLineIndex
                )
            ).await()
        }.onFailure {
            Log.e("RTC", "addCallerCandidate FAILED", it)
        }
    }

    suspend fun addCalleeCandidate(roomId: String, candidate: IceCandidate) {
        runCatching {
            calleeCandidates(roomId).add(
                mapOf(
                    KEY_CANDIDATE to candidate.sdp,
                    KEY_SDP_MID to (candidate.sdpMid.orEmpty()),
                    KEY_SDP_MLINE_INDEX to candidate.sdpMLineIndex
                )
            ).await()
        }.onFailure {
            Log.e("RTC", "addCalleeCandidate FAILED", it)
        }
    }

    fun listenCallerCandidates(
        roomId: String,
        onCandidate: (IceCandidate) -> Unit
    ): ListenerRegistration {
        return callerCandidates(roomId).addSnapshotListener { snap, _ ->
            snap?.documentChanges?.filter {
                it.type == DocumentChange.Type.ADDED
            }?.forEach { dc ->
                val data = dc.document.data
                val sdpMid = (data[KEY_SDP_MID] as? String).orEmpty()
                val sdpMLineIndex = (data[KEY_SDP_MLINE_INDEX] as? Long)?.toInt()
                val candidateStr = data[KEY_CANDIDATE] as? String

                if (sdpMLineIndex != null && candidateStr != null) {
                    onCandidate(IceCandidate(sdpMid, sdpMLineIndex, candidateStr))
                }

                Log.d("RTC", "REMOTE ICE DOC ADDED id=${dc.document.id}")
            }
        }
    }

    fun listenCalleeCandidates(
        roomId: String,
        onCandidate: (IceCandidate) -> Unit
    ): ListenerRegistration {
        return calleeCandidates(roomId).addSnapshotListener { snap, _ ->
            snap?.documentChanges?.filter {
                it.type == DocumentChange.Type.ADDED
            }?.forEach { dc ->
                val data = dc.document.data
                val sdpMid = (data[KEY_SDP_MID] as? String).orEmpty()
                val sdpMLineIndex = (data[KEY_SDP_MLINE_INDEX] as? Long)?.toInt()
                val candidateStr = data[KEY_CANDIDATE] as? String

                if (sdpMLineIndex != null && candidateStr != null) {
                    onCandidate(IceCandidate(sdpMid, sdpMLineIndex, candidateStr))
                }

                Log.d("RTC", "REMOTE ICE DOC ADDED id=${dc.document.id}")
            }
        }
    }

    suspend fun resetRoom(roomId: String) {
        val room = roomRef(roomId)

        // delete offer/answer docs
        runCatching { room.collection("offer").document("sdp").delete().await() }
        runCatching { room.collection("answer").document("sdp").delete().await() }

        // delete candidates (best-effort; for testing)
        fun deleteAll(col: CollectionReference) = col.get().continueWithTask { task ->
            val batch = db.batch()
            task.result?.documents?.forEach { batch.delete(it.reference) }
            batch.commit()
        }

        runCatching { deleteAll(room.collection("callerCandidates")).await() }
        runCatching { deleteAll(room.collection("calleeCandidates")).await() }
    }


    private companion object {
        const val KEY_TYPE = "type"
        const val KEY_SDP = "sdp"
        const val KEY_CANDIDATE = "candidate"
        const val KEY_SDP_MID = "sdpMid"
        const val KEY_SDP_MLINE_INDEX = "sdpMLineIndex"
        const val KEY_OFFER = "offer"
        const val KEY_ANSWER = "answer"
    }
}