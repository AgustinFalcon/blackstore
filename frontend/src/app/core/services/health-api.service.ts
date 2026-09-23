import { HttpClient } from '@angular/common/http';
import { Injectable, inject } from '@angular/core';
import { Observable } from 'rxjs';
import { API_BASE } from '../api';
import { BaseResponse } from '../models/base-response';

export interface HealthData {
  service: string;
  boundedContext: string;
  storeCoreIntegrationEnabled: boolean;
}

@Injectable({ providedIn: 'root' })
export class HealthApiService {
  private readonly http = inject(HttpClient);
  private readonly baseUrl = API_BASE;

  getHealth(): Observable<BaseResponse<HealthData>> {
    return this.http.get<BaseResponse<HealthData>>(`${this.baseUrl}/health`, {
      headers: { 'X-Trace-Id': crypto.randomUUID() },
    });
  }
}
