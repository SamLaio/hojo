package wtf.anurag.hojo.connectivity

import android.net.Network
import java.net.InetAddress
import javax.inject.Inject
import okhttp3.Dns
import okhttp3.OkHttpClient

class NetworkBoundClientFactory @Inject constructor() {
    fun create(network: Network): OkHttpClient {
        return OkHttpClient.Builder()
                .socketFactory(network.socketFactory)
                .dns(
                        object : Dns {
                            override fun lookup(hostname: String): List<InetAddress> {
                                return network.getAllByName(hostname).toList()
                            }
                        }
                )
                .build()
    }
}
