import { Component, inject } from '@angular/core';
import { CommonModule } from '@angular/common';
import { StateService } from './services/state.service';
import { HeaderComponent } from './components/header/header.component';
import { LlamaConfigComponent } from './components/llama-config/llama-config.component';
import { PiManagerComponent } from './components/pi-manager/pi-manager.component';
import { LogsViewerComponent } from './components/logs-viewer/logs-viewer.component';
import { ChatPlaygroundComponent } from './components/chat-playground/chat-playground.component';

@Component({
  selector: 'app-root',
  standalone: true,
  imports: [
    CommonModule,
    HeaderComponent,
    LlamaConfigComponent,
    PiManagerComponent,
    LogsViewerComponent,
    ChatPlaygroundComponent
  ],
  templateUrl: './app.html',
  styleUrls: ['./app.scss']
})
export class App {
  state = inject(StateService);
}
