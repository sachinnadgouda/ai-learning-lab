import { TestBed } from '@angular/core/testing';
import { HttpClient, provideHttpClient, withInterceptors } from '@angular/common/http';
import { HttpTestingController, provideHttpClientTesting } from '@angular/common/http/testing';
import { provideRouter } from '@angular/router';
import { AuthService } from './auth.service';
import { authInterceptor } from './auth.interceptor';

describe('authInterceptor', () => {
  let http: HttpClient;
  let ctrl: HttpTestingController;
  let auth: AuthService;

  beforeEach(() => {
    sessionStorage.clear();
    TestBed.configureTestingModule({
      providers: [provideRouter([{ path: 'login', children: [] }]), provideHttpClient(withInterceptors([authInterceptor])), provideHttpClientTesting()],
    });
    http = TestBed.inject(HttpClient);
    ctrl = TestBed.inject(HttpTestingController);
    auth = TestBed.inject(AuthService);
  });

  it('adds the admin header to API calls only', () => {
    auth.login('secret-token-123');
    http.get('/api/v1/flags').subscribe();
    expect(ctrl.expectOne('/api/v1/flags').request.headers.get('X-Demo-Admin')).toBe('secret-token-123');
    http.get('/assets/logo.svg').subscribe();
    expect(ctrl.expectOne('/assets/logo.svg').request.headers.has('X-Demo-Admin')).toBe(false);
  });

  it('keeps the token in sessionStorage only', () => {
    auth.login('secret-token-123');
    expect(sessionStorage.getItem('demo.adminToken')).toBe('secret-token-123');
    expect(localStorage.getItem('demo.adminToken')).toBeNull();
  });

  it('signs out on 401', () => {
    auth.login('bad-token-value');
    http.get('/api/v1/flags').subscribe({ error: () => undefined });
    ctrl.expectOne('/api/v1/flags').flush({ title: 'Unauthorized' }, { status: 401, statusText: 'Unauthorized' });
    expect(auth.authenticated()).toBe(false);
  });
});
