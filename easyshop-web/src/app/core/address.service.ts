import { HttpClient } from '@angular/common/http';
import { Injectable, inject } from '@angular/core';
import { Observable, map } from 'rxjs';
import { Address, ApiResponse, CreateAddressRequest } from './api-types';

@Injectable({ providedIn: 'root' })
export class AddressService {
  private readonly http = inject(HttpClient);

  list(): Observable<Address[]> {
    return this.http
      .get<ApiResponse<Address[]>>('/api/v1/users/me/addresses')
      .pipe(map((res) => res.data));
  }

  add(request: CreateAddressRequest): Observable<Address> {
    return this.http
      .post<ApiResponse<Address>>('/api/v1/users/me/addresses', request)
      .pipe(map((res) => res.data));
  }
}
