package com.trevorism.bean

import com.trevorism.https.AppClientSecureHttpClient
import jakarta.inject.Named

@jakarta.inject.Singleton
@Named("appSecureHttpClient")
class SecureHttpClientBean extends AppClientSecureHttpClient {
}
